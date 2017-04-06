/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.activiti.rest.service.api.repository;

import javax.servlet.http.HttpServletResponse;

import org.activiti.bpmn.model.BpmnModel;
import org.activiti.engine.*;
import org.activiti.engine.history.HistoricActivityInstance;
import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.history.HistoricTaskInstance;
import org.activiti.engine.history.HistoricTaskInstanceQuery;
import org.activiti.engine.identity.User;
import org.activiti.engine.impl.RepositoryServiceImpl;
import org.activiti.engine.impl.bpmn.behavior.SubProcessActivityBehavior;
import org.activiti.engine.impl.persistence.entity.ExecutionEntity;
import org.activiti.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.activiti.engine.impl.pvm.PvmTransition;
import org.activiti.engine.impl.pvm.ReadOnlyProcessDefinition;
import org.activiti.engine.impl.pvm.delegate.ActivityBehavior;
import org.activiti.engine.impl.pvm.process.ActivityImpl;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.repository.ProcessDefinitionQuery;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.activiti.engine.task.TaskQuery;
import org.activiti.image.ProcessDiagramGenerator;
import org.activiti.rest.service.api.ActivityUtil;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Frederik Heremans
 */
@RestController
public class DeploymentResourceDataResource extends BaseDeploymentResourceDataResource {

  @RequestMapping(value="/repository/deployments/{deploymentId}/resourcedata/{resourceId}", method = RequestMethod.GET)
  public @ResponseBody byte[] getDeploymentResource(@PathVariable("deploymentId") String deploymentId,
      @PathVariable("resourceId") String resourceId, HttpServletResponse response) {

    return getDeploymentResourceData(deploymentId, resourceId, response);
  }

  /**
   * 读取流程资源
   *  @param processDefinitionId 流程定义ID
   * @param resourceName        资源名称
   */
  @RequestMapping(value = "/repository/deployments-read-resource" , method = RequestMethod.GET)
  public void readResource(@RequestParam("pdid") String processDefinitionId,
                             @RequestParam("resourceName") String resourceName, HttpServletResponse response)
          throws Exception {
    ProcessDefinitionQuery pdq = repositoryService.createProcessDefinitionQuery();
    ProcessDefinition pd = pdq.processDefinitionId(processDefinitionId).singleResult();

    // 通过接口读取
    InputStream resourceAsStream = repositoryService.getResourceAsStream(pd.getDeploymentId(), resourceName);

    // 输出资源内容到相应对象
    byte[] b = new byte[1024];
    int len;
    while ((len = resourceAsStream.read(b, 0, 1024)) != -1) {
      response.getOutputStream().write(b, 0, len);
    }

  }


  @Autowired
  TaskService taskService;

  @Autowired
  HistoryService historyService;

  @Autowired
  RuntimeService runtimeService;

  @Autowired
  ProcessEngineConfiguration processEngineConfiguration;

  @RequestMapping(value = "/repository/deployments-trace/{processInstanceId}")
  public void readResource(@PathVariable("processInstanceId") String processInstanceId, HttpServletResponse response)
          throws Exception {
    ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
    BpmnModel bpmnModel = repositoryService.getBpmnModel(processInstance.getProcessDefinitionId());
    ProcessDefinitionEntity processDefinition = (ProcessDefinitionEntity) ((RepositoryServiceImpl) repositoryService)
            .getDeployedProcessDefinition(processInstance.getProcessDefinitionId());
    //当前节点
    List<String> activeActivityIds = runtimeService.getActiveActivityIds(processInstanceId);
    ProcessDiagramGenerator diagramGenerator = processEngineConfiguration.getProcessDiagramGenerator();
    List<String> highLightedFlows = getHighLightedFlows(processDefinition, processInstance.getId());
    InputStream imageStream =diagramGenerator.generateDiagram(bpmnModel, "png", activeActivityIds, highLightedFlows);

    // 输出资源内容到相应对象
    byte[] b = new byte[1024];
    int len;
    while ((len = imageStream.read(b, 0, 1024)) != -1) {
      response.getOutputStream().write(b, 0, len);
    }
  }

  private List<String> getHighLightedFlows(ProcessDefinitionEntity processDefinition, String processInstanceId) {
    List<String> highLightedFlows = new ArrayList<String>();
    List<HistoricActivityInstance> historicActivityInstances = historyService
            .createHistoricActivityInstanceQuery()
            .processInstanceId(processInstanceId)
            .orderByHistoricActivityInstanceStartTime().asc().list();

    List<String> historicActivityInstanceList = new ArrayList<String>();
    for (HistoricActivityInstance hai : historicActivityInstances) {
      historicActivityInstanceList.add(hai.getActivityId());
    }

    // add current activities to list
    List<String> highLightedActivities = runtimeService.getActiveActivityIds(processInstanceId);
    historicActivityInstanceList.addAll(highLightedActivities);

    // activities and their sequence-flows
    for (ActivityImpl activity : processDefinition.getActivities()) {
      int index = historicActivityInstanceList.indexOf(activity.getId());

      if (index >= 0 && index + 1 < historicActivityInstanceList.size()) {
        List<PvmTransition> pvmTransitionList = activity
                .getOutgoingTransitions();
        for (PvmTransition pvmTransition : pvmTransitionList) {
          String destinationFlowId = pvmTransition.getDestination().getId();
          if (destinationFlowId.equals(historicActivityInstanceList.get(index + 1))) {
            highLightedFlows.add(pvmTransition.getId());
          }
        }
      }
    }
    return highLightedFlows;
  }

  @Autowired
  protected IdentityService identityService;

  /**
   * 读取跟踪数据
   *
   * @param processInstanceId
   * @return
   * @throws Exception
   */
  @RequestMapping(value = "/repository/deployments-data/{processInstanceId}")
  @ResponseBody
  public List<Map<String, Object>> readActivityDatas(@PathVariable("processInstanceId") String processInstanceId) throws Exception {
    ExecutionEntity executionEntity = (ExecutionEntity) runtimeService.createExecutionQuery().executionId(processInstanceId).singleResult();
    List<String> activeActivityIds = runtimeService.getActiveActivityIds(processInstanceId);

    RepositoryServiceImpl repositoryServiceImpl = (RepositoryServiceImpl) repositoryService;
    ReadOnlyProcessDefinition deployedProcessDefinition = repositoryServiceImpl
            .getDeployedProcessDefinition(executionEntity.getProcessDefinitionId());

    ProcessDefinitionEntity processDefinition = (ProcessDefinitionEntity) deployedProcessDefinition;
    List<ActivityImpl> activitiList = processDefinition.getActivities();//获得当前任务的所有节点

    List<Map<String, Object>> activityInfos = new ArrayList<Map<String, Object>>();
    for (ActivityImpl activity : activitiList) {

      ActivityBehavior activityBehavior = activity.getActivityBehavior();

      boolean currentActiviti = false;
      // 当前节点
      String activityId = activity.getId();
      if (activeActivityIds.contains(activityId)) {
        currentActiviti = true;
      }
      Map<String, Object> activityImageInfo = packageSingleActivitiInfo(activity, executionEntity.getId(), currentActiviti);
      activityInfos.add(activityImageInfo);

      // 处理子流程
      if (activityBehavior instanceof SubProcessActivityBehavior) {
        List<ActivityImpl> innerActivityList = activity.getActivities();
        for (ActivityImpl innerActivity : innerActivityList) {
          String innerActivityId = innerActivity.getId();
          if (activeActivityIds.contains(innerActivityId)) {
            currentActiviti = true;
          } else {
            currentActiviti = false;
          }
          activityImageInfo = packageSingleActivitiInfo(innerActivity, executionEntity.getId(), currentActiviti);
          activityInfos.add(activityImageInfo);
        }
      }

    }

    return activityInfos;
  }

  /**
   * 封装输出信息，包括：当前节点的X、Y坐标、变量信息、任务类型、任务描述
   *
   * @param activity
   * @param currentActiviti
   * @return
   */
  private Map<String, Object> packageSingleActivitiInfo(ActivityImpl activity, String executionId,
                                                        boolean currentActiviti) throws Exception {
    Map<String, Object> activityInfo = new HashMap<String, Object>();
    activityInfo.put("currentActiviti", currentActiviti);

    // 设置图形的XY坐标以及宽度、高度
    setSizeAndPositonInfo(activity, activityInfo);

    Map<String, Object> vars = new HashMap<String, Object>();
    Map<String, Object> properties = activity.getProperties();
    vars.put("任务类型", ActivityUtil.getZhActivityType(properties.get("type").toString()));
    vars.put("任务名称", properties.get("name"));
    vars.put("TaskDefinition", properties.get("taskDefinition"));

    // 当前节点的task
    //if (currentActiviti) {
      setCurrentTaskInfo(executionId, activity.getId(), vars);
    //}

//    logger.debug("trace variables: {}", vars);
    activityInfo.put("vars", vars);
    return activityInfo;
  }

  /**
   * 获取当前节点信息
   *
   * @return
   */
  private void setCurrentTaskInfo(String executionId, String activityId, Map<String, Object> vars) {

    List<HistoricActivityInstance> list = historyService.createHistoricActivityInstanceQuery().processInstanceId(executionId).activityId(activityId).orderByHistoricActivityInstanceStartTime().desc().list();
    HistoricActivityInstance currentTask = null;
    if(list.size()>0){
      currentTask = list.get(0);
    }

    if (currentTask == null) return;

    String assignee = currentTask.getAssignee();
    if (assignee != null) {
      System.out.print(assignee);
      vars.put("当前处理人", assignee);
      vars.put("创建时间", new SimpleDateFormat("yyyy-MM-dd hh:mm:ss").format(currentTask.getStartTime()));
    } else {
      vars.put("任务状态", "未签收");
    }

  }

  /**
   * 设置宽度、高度、坐标属性
   *
   * @param activity
   * @param activityInfo
   */
  private void setSizeAndPositonInfo(ActivityImpl activity, Map<String, Object> activityInfo) {
    activityInfo.put("width", activity.getWidth());
    activityInfo.put("height", activity.getHeight());
    activityInfo.put("x", activity.getX());
    activityInfo.put("y", activity.getY());
  }
  public static void main(String[] args) throws Exception {
    new DeploymentResourceDataResource().readActivityDatas("7501");
  }
}
