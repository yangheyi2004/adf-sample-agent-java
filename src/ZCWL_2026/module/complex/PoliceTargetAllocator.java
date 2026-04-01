package ZCWL_2026.module.complex;

import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;

/**
 * 警察目标分配器 - 完整优化版
 * 
 * 功能：
 * 1. 将道路分配到各个警察（基于均衡聚类）
 * 2. 处理救援请求，分配任务
 * 3. 任务持久化，避免重复分配
 * 4. 检测任务完成状态
 */
public class PoliceTargetAllocator extends adf.core.component.module.complex.PoliceTargetAllocator {

    // ==================== 优先级常量 ====================
    private static final int PRIORITY_HELP_REQUEST = 1;
    private static final int PRIORITY_REFUGE_PATH = 0;
    private static final int PRIORITY_FIRE_PATH = 2;

    private static final int MAX_POLICE_PER_TASK = 1;
    private static final int TASK_EXPIRE_TIME = 50;

    // ==================== 数据结构 ====================
    private PathPlanning pathPlanning;
    private Clustering policeClustering;
    private Map<EntityID, PoliceInfo> policeMap;
    private PriorityQueue<Task> taskQueue;
    
    // 任务分配持久化
    private Map<EntityID, Integer> taskAssignCount;      // 任务 -> 已分配警察数
    private Map<EntityID, Set<EntityID>> taskToPolice;    // 任务 -> 负责的警察列表
    private Map<EntityID, EntityID> policeToTask;         // 警察 -> 当前任务
    private Set<EntityID> completedTasks;                 // 已完成的任务
    private Set<EntityID> ignoredTasks;                   // 已忽略的任务
    private Map<EntityID, Integer> taskStartTime;         // 任务开始时间
    
    // 任务源
    private Set<EntityID> helpRequestTasks;
    private Set<EntityID> refugePaths;
    private Set<EntityID> firePaths;
    
    // 区域划分
    private Map<EntityID, List<EntityID>> policeZones;
    private Map<EntityID, EntityID> roadToPoliceMap;
    
    // 状态
    private boolean isInitialized;

    public PoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                  ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);

        this.policeMap = new HashMap<>();
        this.taskQueue = new PriorityQueue<>((t1, t2) -> Integer.compare(t1.priority, t2.priority));
        this.taskAssignCount = new HashMap<>();
        this.taskToPolice = new HashMap<>();
        this.policeToTask = new HashMap<>();
        this.taskStartTime = new HashMap<>();
        
        this.helpRequestTasks = new HashSet<>();
        this.refugePaths = new HashSet<>();
        this.firePaths = new HashSet<>();
        
        this.completedTasks = new HashSet<>();
        this.ignoredTasks = new HashSet<>();
        this.policeZones = new HashMap<>();
        this.roadToPoliceMap = new HashMap<>();
        this.isInitialized = false;

        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                    "PoliceTargetAllocator.PathPlanning",
                    "ZCWL_2026.module.algorithm.PathPlanning");
                this.policeClustering = moduleManager.getModule(
                    "PoliceTargetAllocator.Clustering",
                    "ZCWL_2026.module.algorithm.PoliceBalancedClustering");
                break;
        }
        if (this.pathPlanning != null) registerModule(this.pathPlanning);
        if (this.policeClustering != null) registerModule(this.policeClustering);
        
        System.err.println("[警察分配器] 初始化完成，每个任务最多 " + MAX_POLICE_PER_TASK + " 个警察");
    }

    @Override
    public PoliceTargetAllocator resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        initialize();
        return this;
    }

    @Override
    public PoliceTargetAllocator preparate() {
        super.preparate();
        if (this.getCountPrecompute() >= 2) return this;
        initialize();
        return this;
    }

    private void initialize() {
        // 初始化警察信息
        for (EntityID id : this.worldInfo.getEntityIDsOfType(POLICE_FORCE)) {
            policeMap.put(id, new PoliceInfo(id));
        }
        initializeRoads();
        divideZones();
        isInitialized = true;
        System.err.println("[警察分配器] 初始化完成，警察数量: " + policeMap.size());
    }

    private void initializeRoads() {
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE)) {
            addNeighbourRoads(e, refugePaths);
        }
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(FIRE_STATION)) {
            addNeighbourRoads(e, firePaths);
        }
    }

    private void addNeighbourRoads(StandardEntity building, Set<EntityID> roadSet) {
        if (!(building instanceof Building)) return;
        for (EntityID neighbourId : ((Building) building).getNeighbours()) {
            StandardEntity neighbour = this.worldInfo.getEntity(neighbourId);
            if (neighbour instanceof Road) roadSet.add(neighbourId);
        }
    }

    private void divideZones() {
        if (this.policeClustering == null) {
            System.err.println("[警察分配器] 聚类模块不可用，无法划分区域");
            return;
        }

        this.policeClustering.calc();
        List<EntityID> policeIds = new ArrayList<>(policeMap.keySet());
        
        for (EntityID policeId : policeIds) {
            int clusterIndex = this.policeClustering.getClusterIndex(policeId);
            if (clusterIndex >= 0) {
                Collection<EntityID> zoneRoads = this.policeClustering.getClusterEntityIDs(clusterIndex);
                if (zoneRoads != null && !zoneRoads.isEmpty()) {
                    List<EntityID> roadList = new ArrayList<>(zoneRoads);
                    policeZones.put(policeId, roadList);
                    for (EntityID roadId : roadList) {
                        roadToPoliceMap.put(roadId, policeId);
                    }
                    System.err.println("[警察分配器] 警察 " + policeId + " 负责区域道路数: " + roadList.size());
                } else {
                    policeZones.put(policeId, new ArrayList<>());
                }
            } else {
                policeZones.put(policeId, new ArrayList<>());
            }
        }
    }

    // ==================== 消息处理 ====================
    private void processMessages(MessageManager messageManager) {
        for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
            if (message instanceof MessageFireBrigade) {
                handleFiremanMessage((MessageFireBrigade) message);
            } else if (message instanceof MessageAmbulanceTeam) {
                handleAmbulanceMessage((MessageAmbulanceTeam) message);
            } else if (message instanceof MessageCivilian || message instanceof MessagePoliceForce ||
                       message instanceof MessageFireBrigade || message instanceof MessageAmbulanceTeam) {
                handleTrappedMessage(message);
            } else if (message instanceof MessageBuilding) {
                handleFireMessage((MessageBuilding) message);
            } else if (message instanceof MessagePoliceForce) {
                handlePoliceMessage((MessagePoliceForce) message);
            } else if (message instanceof MessageReport) {
                handleReportMessage((MessageReport) message);
            }
        }
    }

    private void handleFiremanMessage(MessageFireBrigade mfb) {
        EntityID target = mfb.getTargetID();
        if (target != null && !ignoredTasks.contains(target) && !helpRequestTasks.contains(target)) {
            helpRequestTasks.add(target);
            addTask(target, PRIORITY_HELP_REQUEST);
        }
    }

    private void handleAmbulanceMessage(MessageAmbulanceTeam mat) {
        EntityID target = mat.getTargetID();
        if (target != null && !ignoredTasks.contains(target) && !helpRequestTasks.contains(target)) {
            helpRequestTasks.add(target);
            addTask(target, PRIORITY_HELP_REQUEST);
        }
    }

    private void handleTrappedMessage(CommunicationMessage message) {
        EntityID trappedPosition = null;
        EntityID trappedId = null;
        
        if (message instanceof MessageCivilian) {
            MessageCivilian msg = (MessageCivilian) message;
            if (msg.isBuriednessDefined() && msg.getBuriedness() > 0 && msg.isPositionDefined()) {
                trappedPosition = msg.getPosition();
                trappedId = msg.getAgentID();
            }
        } else if (message instanceof MessageFireBrigade) {
            MessageFireBrigade msg = (MessageFireBrigade) message;
            if (msg.isBuriednessDefined() && msg.getBuriedness() > 0 && msg.isPositionDefined()) {
                trappedPosition = msg.getPosition();
                trappedId = msg.getAgentID();
            }
        } else if (message instanceof MessagePoliceForce) {
            MessagePoliceForce msg = (MessagePoliceForce) message;
            if (msg.isBuriednessDefined() && msg.getBuriedness() > 0 && msg.isPositionDefined()) {
                trappedPosition = msg.getPosition();
                trappedId = msg.getAgentID();
            }
        } else if (message instanceof MessageAmbulanceTeam) {
            MessageAmbulanceTeam msg = (MessageAmbulanceTeam) message;
            if (msg.isBuriednessDefined() && msg.getBuriedness() > 0 && msg.isPositionDefined()) {
                trappedPosition = msg.getPosition();
                trappedId = msg.getAgentID();
            }
        }
        
        if (trappedPosition != null && trappedId != null) {
            StandardEntity posEntity = this.worldInfo.getEntity(trappedPosition);
            if (posEntity instanceof Building) {
                Building building = (Building) posEntity;
                for (EntityID neighbourId : building.getNeighbours()) {
                    StandardEntity neighbour = this.worldInfo.getEntity(neighbourId);
                    if (neighbour instanceof Road && !ignoredTasks.contains(neighbourId)) {
                        helpRequestTasks.add(neighbourId);
                        addTask(neighbourId, PRIORITY_HELP_REQUEST);
                    }
                }
            } else if (posEntity instanceof Road && !ignoredTasks.contains(trappedPosition)) {
                helpRequestTasks.add(trappedPosition);
                addTask(trappedPosition, PRIORITY_HELP_REQUEST);
            }
        }
    }

    private void handleFireMessage(MessageBuilding mb) {
        if (mb.isFierynessDefined() && mb.getFieryness() > 0) {
            Building building = (Building) this.worldInfo.getEntity(mb.getBuildingID());
            if (building != null) {
                for (EntityID neighbourId : building.getNeighbours()) {
                    StandardEntity neighbour = this.worldInfo.getEntity(neighbourId);
                    if (neighbour instanceof Road && !ignoredTasks.contains(neighbourId)) {
                        firePaths.add(neighbourId);
                        addTask(neighbourId, PRIORITY_FIRE_PATH);
                    }
                }
            }
        }
    }

    private void handlePoliceMessage(MessagePoliceForce mpf) {
        PoliceInfo info = policeMap.get(mpf.getAgentID());
        if (info == null) {
            info = new PoliceInfo(mpf.getAgentID());
            policeMap.put(mpf.getAgentID(), info);
        }
        if (mpf.isPositionDefined()) info.currentPosition = mpf.getPosition();
        int currentTime = this.agentInfo.getTime();
        if (currentTime >= info.commandTime + 2) updatePoliceInfo(info, mpf);
    }

    private void updatePoliceInfo(PoliceInfo info, MessagePoliceForce message) {
        if (message.isBuriednessDefined() && message.getBuriedness() > 0) {
            info.isBusy = false;
            info.canNewAction = false;
            if (info.currentTask != null) releaseTask(info.currentTask, info.id);
            info.currentTask = null;
            return;
        }
        
        switch (message.getAction()) {
            case MessagePoliceForce.ACTION_REST:
                info.canNewAction = true;
                info.isBusy = false;
                if (info.currentTask != null) {
                    releaseTask(info.currentTask, info.id);
                    info.currentTask = null;
                }
                break;
            case MessagePoliceForce.ACTION_MOVE:
                if (message.getTargetID() != null && message.getTargetID().equals(info.currentTask)) {
                    info.isBusy = true;
                } else {
                    info.canNewAction = true;
                    if (info.currentTask != null) {
                        releaseTask(info.currentTask, info.id);
                        info.currentTask = null;
                    }
                }
                break;
            case MessagePoliceForce.ACTION_CLEAR:
                info.isBusy = true;
                info.canNewAction = false;
                break;
        }
    }

    private void handleReportMessage(MessageReport report) {
        if (report.isDone()) {
            PoliceInfo info = policeMap.get(report.getSenderID());
            if (info != null && info.currentTask != null) {
                releaseTask(info.currentTask, info.id);
                info.currentTask = null;
                info.isBusy = false;
                info.canNewAction = true;
                System.err.println("[警察分配器] ✅ 警察 " + report.getSenderID() + " 完成任务");
            }
        }
    }

    // ==================== 任务管理 ====================
    
    private void addTask(EntityID target, int priority) {
        if (ignoredTasks.contains(target)) return;
        if (completedTasks.contains(target)) return;
        
        // 检查任务是否已被分配满
        int currentCount = taskAssignCount.getOrDefault(target, 0);
        if (currentCount >= MAX_POLICE_PER_TASK) {
            return;
        }
        
        for (Task task : taskQueue) {
            if (task.target.equals(target) && task.priority <= priority) {
                return;
            }
        }
        
        taskQueue.offer(new Task(target, priority, this.agentInfo.getTime()));
    }

    private void updateTaskQueue() {
        int currentTime = this.agentInfo.getTime();

        // 清理已完成的任务
        for (EntityID completed : completedTasks) {
            helpRequestTasks.remove(completed);
            refugePaths.remove(completed);
            firePaths.remove(completed);
            taskQueue.removeIf(task -> task.target.equals(completed));
        }
        
        // 清理过期的任务
        List<Task> expired = new ArrayList<>();
        for (Task task : taskQueue) {
            if (currentTime - task.createTime > TASK_EXPIRE_TIME) {
                expired.add(task);
                // 释放该任务的所有警察
                Set<EntityID> assignedPolice = taskToPolice.get(task.target);
                if (assignedPolice != null) {
                    for (EntityID policeId : assignedPolice) {
                        PoliceInfo info = policeMap.get(policeId);
                        if (info != null && task.target.equals(info.currentTask)) {
                            info.currentTask = null;
                            info.isBusy = false;
                            info.canNewAction = true;
                        }
                        policeToTask.remove(policeId);
                    }
                    taskToPolice.remove(task.target);
                }
                taskAssignCount.remove(task.target);
                taskStartTime.remove(task.target);
            }
        }
        taskQueue.removeAll(expired);
        
        // 添加新任务
        for (EntityID road : helpRequestTasks) addTask(road, PRIORITY_HELP_REQUEST);
        for (EntityID road : refugePaths) addTask(road, PRIORITY_REFUGE_PATH);
        for (EntityID road : firePaths) addTask(road, PRIORITY_FIRE_PATH);
    }

    private EntityID findBestPoliceForTask(Task task) {
        // 优先使用负责该道路的警察
        EntityID responsiblePolice = roadToPoliceMap.get(task.target);
        if (responsiblePolice != null) {
            PoliceInfo info = policeMap.get(responsiblePolice);
            if (info != null && info.currentTask == null && isReachable(responsiblePolice, task.target)) {
                return responsiblePolice;
            }
        }

        // 全局查找空闲警察
        EntityID bestPolice = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<EntityID, PoliceInfo> entry : policeMap.entrySet()) {
            PoliceInfo info = entry.getValue();
            // 只考虑空闲且未分配任务的警察
            if (info.currentTask == null && !policeToTask.containsKey(entry.getKey())) {
                if (isReachable(entry.getKey(), task.target)) {
                    double dist = getDistance(entry.getKey(), task.target);
                    if (dist < bestDistance) {
                        bestDistance = dist;
                        bestPolice = entry.getKey();
                    }
                }
            }
        }
        
        return bestPolice;
    }

    private void assignTasks() {
        // 先更新所有警察的任务完成状态
        updatePoliceTaskStatus();
        
        List<Task> tasks = new ArrayList<>(taskQueue);
        tasks.sort(Comparator.comparingInt(t -> t.priority));
        
        int assignedCount = 0;
        Set<EntityID> assignedTasksThisRound = new HashSet<>();
        
        for (Task task : tasks) {
            if (assignedTasksThisRound.contains(task.target)) continue;
            
            int currentCount = taskAssignCount.getOrDefault(task.target, 0);
            if (currentCount >= MAX_POLICE_PER_TASK) {
                taskQueue.remove(task);
                continue;
            }
            
            EntityID bestPolice = findBestPoliceForTask(task);
            if (bestPolice != null) {
                assignTaskToPolice(bestPolice, task.target, task.priority);
                assignedTasksThisRound.add(task.target);
                assignedCount++;
                taskQueue.remove(task);
            }
        }
        
        if (assignedCount > 0) {
            System.err.println("[警察分配器] ✅ 本轮分配了 " + assignedCount + " 个任务");
        }
        
        // 清理已分配的任务源
        helpRequestTasks.removeAll(assignedTasksThisRound);
        refugePaths.removeAll(assignedTasksThisRound);
        firePaths.removeAll(assignedTasksThisRound);
    }
    
    /**
     * 更新警察的任务完成状态（基于实际世界状态）
     */
    private void updatePoliceTaskStatus() {
        // 检查每个警察负责的任务是否已完成
        for (Map.Entry<EntityID, EntityID> entry : new HashMap<>(policeToTask).entrySet()) {
            EntityID policeId = entry.getKey();
            EntityID taskId = entry.getValue();
            
            // 检查任务是否已完成（道路已无路障）
            if (!hasBlockades(taskId)) {
                releaseTask(taskId, policeId);
                PoliceInfo info = policeMap.get(policeId);
                if (info != null) {
                    info.currentTask = null;
                    info.isBusy = false;
                    info.canNewAction = true;
                }
                if (!completedTasks.contains(taskId)) {
                    completedTasks.add(taskId);
                    System.err.println("[警察分配器] 📍 任务 " + taskId + " 已完成（道路已清理）");
                }
            }
        }
        
        // 清理已完成的任务
        for (EntityID completed : completedTasks) {
            taskAssignCount.remove(completed);
            taskStartTime.remove(completed);
            helpRequestTasks.remove(completed);
            refugePaths.remove(completed);
            firePaths.remove(completed);
        }
    }
    
    private void releaseTask(EntityID task, EntityID policeId) {
        // 更新任务分配计数
        int count = taskAssignCount.getOrDefault(task, 0);
        if (count > 0) {
            taskAssignCount.put(task, count - 1);
        }
        
        // 更新任务-警察映射
        Set<EntityID> policeSet = taskToPolice.get(task);
        if (policeSet != null) {
            policeSet.remove(policeId);
            if (policeSet.isEmpty()) {
                taskToPolice.remove(task);
            }
        }
        
        // 更新警察-任务映射
        policeToTask.remove(policeId);
    }

    private void assignTaskToPolice(EntityID policeId, EntityID task, int priority) {
        // 更新任务分配计数
        taskAssignCount.put(task, taskAssignCount.getOrDefault(task, 0) + 1);
        
        // 更新任务-警察映射
        taskToPolice.computeIfAbsent(task, k -> new HashSet<>()).add(policeId);
        
        // 更新警察-任务映射
        policeToTask.put(policeId, task);
        
        // 更新警察信息
        PoliceInfo info = policeMap.get(policeId);
        if (info != null) {
            if (info.currentTask != null && !info.currentTask.equals(task)) {
                releaseTask(info.currentTask, info.id);
            }
            info.currentTask = task;
            info.currentTaskPriority = priority;
            info.isBusy = true;
            info.canNewAction = false;
            info.commandTime = this.agentInfo.getTime();
            taskStartTime.putIfAbsent(task, this.agentInfo.getTime());
        }
    }

    private boolean hasBlockades(EntityID roadId) {
        StandardEntity entity = this.worldInfo.getEntity(roadId);
        if (!(entity instanceof Road)) return false;
        Road road = (Road) entity;
        return road.isBlockadesDefined() && !road.getBlockades().isEmpty();
    }

    private double getDistance(EntityID from, EntityID to) {
        if (this.pathPlanning != null) {
            return this.pathPlanning.getDistance(from, to);
        }
        StandardEntity fromEntity = this.worldInfo.getEntity(from);
        StandardEntity toEntity = this.worldInfo.getEntity(to);
        if (fromEntity != null && toEntity != null) {
            return this.worldInfo.getDistance(fromEntity, toEntity);
        }
        return Double.MAX_VALUE;
    }

    private boolean isReachable(EntityID policeId, EntityID targetPos) {
        if (this.pathPlanning == null) return true;
        if (targetPos == null) return false;
        
        StandardEntity agent = this.worldInfo.getEntity(policeId);
        if (!(agent instanceof PoliceForce)) return false;
        PoliceForce police = (PoliceForce) agent;
        if (!police.isPositionDefined()) return false;
        
        List<EntityID> path = this.pathPlanning.getResult(police.getPosition(), targetPos);
        return path != null && !path.isEmpty();
    }

    // ==================== 公共接口 ====================
    
    @Override
    public Map<EntityID, EntityID> getResult() {
        Map<EntityID, EntityID> result = new HashMap<>();
        for (Map.Entry<EntityID, PoliceInfo> entry : policeMap.entrySet()) {
            PoliceInfo info = entry.getValue();
            if (info.currentTask != null && !completedTasks.contains(info.currentTask)) {
                result.put(entry.getKey(), info.currentTask);
            }
        }
        return result;
    }

    @Override
    public PoliceTargetAllocator calc() {
        if (!isInitialized) {
            initialize();
        }
        updateTaskQueue();
        assignTasks();
        return this;
    }

    @Override
    public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;
        if (this.pathPlanning != null) this.pathPlanning.updateInfo(messageManager);
        
        // 处理消息
        processMessages(messageManager);
        
        // 清理已清理的道路
        for (EntityID id : this.worldInfo.getChanged().getChangedEntities()) {
            StandardEntity entity = this.worldInfo.getEntity(id);
            if (entity instanceof Road) {
                Road road = (Road) entity;
                if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
                    ignoredTasks.add(id);
                    // 释放该道路的所有任务
                    Set<EntityID> assignedPolice = taskToPolice.get(id);
                    if (assignedPolice != null) {
                        for (EntityID policeId : assignedPolice) {
                            PoliceInfo info = policeMap.get(policeId);
                            if (info != null && id.equals(info.currentTask)) {
                                info.currentTask = null;
                                info.isBusy = false;
                                info.canNewAction = true;
                            }
                            policeToTask.remove(policeId);
                        }
                        taskToPolice.remove(id);
                    }
                    taskAssignCount.remove(id);
                    if (!completedTasks.contains(id)) {
                        completedTasks.add(id);
                    }
                }
            }
        }
        
        return this;
    }

    // ==================== 内部类 ====================
    
    private static class Task {
        EntityID target;
        int priority;
        int createTime;
        Task(EntityID target, int priority, int createTime) {
            this.target = target;
            this.priority = priority;
            this.createTime = createTime;
        }
    }

    private static class PoliceInfo {
        EntityID id;
        EntityID currentPosition;
        EntityID currentTask;
        int currentTaskPriority;
        boolean isBusy;
        boolean canNewAction;
        int commandTime;
        
        PoliceInfo(EntityID id) {
            this.id = id;
            this.currentTask = null;
            this.currentTaskPriority = PRIORITY_FIRE_PATH;
            this.isBusy = false;
            this.canNewAction = true;
            this.commandTime = -1;
        }
    }
}