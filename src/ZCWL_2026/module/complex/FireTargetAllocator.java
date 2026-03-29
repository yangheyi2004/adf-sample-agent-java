package ZCWL_2026.module.complex;

import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.centralized.CommandFire;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;

public class FireTargetAllocator extends adf.core.component.module.complex.FireTargetAllocator {

    // 优先级常量（数字越小优先级越高）
    private static final int PRIORITY_TYPE_FIRE = 0;      // 消防员被困
    private static final int PRIORITY_TYPE_POLICE = 1;    // 警察被困
    private static final int PRIORITY_TYPE_AMBULANCE = 2; // 救护车被困
    private static final int PRIORITY_TYPE_CIVILIAN = 3;  // 平民被困
    private static final int PRIORITY_FIRE = 4;            // 灭火任务

    private static final int MAX_AGENTS_PER_RESCUE = 5;    // 每个救援请求最多分配的消防员数量
    private static final int TASK_EXPIRE_TIME = 30;

    private PathPlanning pathPlanning;
    private Map<EntityID, FireInfo> firemanMap;
    private PriorityQueue<Task> taskQueue;
    
    // 任务持久化
    private Map<EntityID, EntityID> assignedFiremanTasks;
    private Set<EntityID> tasksInProgress;
    
    private Map<EntityID, Integer> rescueAssignCount;
    private Set<EntityID> completedRescues;
    private Set<EntityID> processedRescueMessages;
    
    private Set<EntityID> rescueRequestTargets;   // 救援请求（建筑ID）
    private Set<EntityID> fireTargets;            // 灭火任务（建筑ID）
    
    // 记录每个建筑对应的救援优先级（取最小值，即最高优先级）
    private Map<EntityID, Integer> rescuePriorityMap;
    
    // 记录建筑内被困人员的映射
    private Map<EntityID, Set<EntityID>> buildingToVictims;

    public FireTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);

        this.firemanMap = new HashMap<>();
        this.taskQueue = new PriorityQueue<>((t1, t2) -> Integer.compare(t1.priority, t2.priority));
        
        this.assignedFiremanTasks = new HashMap<>();
        this.tasksInProgress = new HashSet<>();
        
        this.rescueAssignCount = new HashMap<>();
        this.completedRescues = new HashSet<>();
        this.processedRescueMessages = new HashSet<>();
        this.rescueRequestTargets = new HashSet<>();
        this.fireTargets = new HashSet<>();
        this.rescuePriorityMap = new HashMap<>();
        this.buildingToVictims = new HashMap<>();

        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                    "FireTargetAllocator.PathPlanning",
                    "ZCWL_2026.module.algorithm.PathPlanning");
                break;
        }
        if (this.pathPlanning != null) {
            registerModule(this.pathPlanning);
        }
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [ZCWL_2026] 消防员分配器已加载                                ║");
        System.err.println("║  救援优先级: 消防员(0) > 警察(1) > 救护车(2) > 平民(3)        ║");
        System.err.println("║  灭火优先级: 4                                                ║");
        System.err.println("║  策略: 只分配给空闲消防员，距离优先                             ║");
        System.err.println("║  每个救援任务最多 " + MAX_AGENTS_PER_RESCUE + " 个消防员          ║");
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
    }

    @Override
    public FireTargetAllocator resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        initialize();
        return this;
    }

    @Override
    public FireTargetAllocator preparate() {
        super.preparate();
        if (this.getCountPrecompute() >= 2) return this;
        initialize();
        return this;
    }

    @Override
    public Map<EntityID, EntityID> getResult() {
        Map<EntityID, EntityID> result = new HashMap<>();
        
        for (Map.Entry<EntityID, EntityID> entry : assignedFiremanTasks.entrySet()) {
            FireInfo info = firemanMap.get(entry.getKey());
            if (info != null && !info.isBusy) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        
        return result;
    }

    @Override
    public FireTargetAllocator calc() {
        updateTaskQueue();
        assignTasks();
        return this;
    }

    @Override
    public FireTargetAllocator updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        processMessages(messageManager);
        removeCompletedTasks();
        checkTaskCompletion();
        return this;
    }

    private void initialize() {
        for (EntityID id : this.worldInfo.getEntityIDsOfType(FIRE_BRIGADE)) {
            firemanMap.put(id, new FireInfo(id));
        }
        System.err.println("[消防员分配器] 初始化完成，消防员数量: " + firemanMap.size());
    }

    /**
     * 统一处理消息入口
     */
    private void processMessages(MessageManager messageManager) {
        for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
            // 处理救援请求（所有类型）
            if (message instanceof MessageCivilian) {
                handleRescueRequest((MessageCivilian) message);
            } else if (message instanceof MessagePoliceForce) {
                handleRescueRequest((MessagePoliceForce) message);
            } else if (message instanceof MessageFireBrigade) {
                handleRescueRequest((MessageFireBrigade) message);
            } else if (message instanceof MessageAmbulanceTeam) {
                handleRescueRequest((MessageAmbulanceTeam) message);
            } else {
                // 其他消息
                handleFireMessage(message);
                handleFiremanMessage(message);
                handleReportMessage(message);
            }
        }
    }

    // ==================== 救援请求处理（根据类型计算优先级） ====================

    private void handleRescueRequest(MessageCivilian msg) {
        StandardEntity targetEntity = this.worldInfo.getEntity(msg.getAgentID());
        EntityID buildingPosition = msg.getPosition();
        
        if (buildingPosition == null && targetEntity instanceof Human) {
            buildingPosition = ((Human) targetEntity).getPosition();
        }
        
        if (buildingPosition == null) {
            System.err.println("[消防员分配器] ⚠️ 无法获取建筑位置，忽略请求");
            return;
        }
        
        StandardEntity buildingEntity = this.worldInfo.getEntity(buildingPosition);
        if (!(buildingEntity instanceof Building)) {
            System.err.println("[消防员分配器] ⚠️ 被困位置不是建筑: " + buildingPosition);
            return;
        }
        
        EntityID victimId = (targetEntity instanceof Human) ? targetEntity.getID() : null;
        int priority = (victimId != null) ? getTypePriorityFromEntity(victimId) : PRIORITY_TYPE_CIVILIAN;
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [消防员分配器] 🚨 收到平民救援请求！                          ║");
        if (victimId != null) {
            System.err.println("║  被困平民 ID: " + victimId + " 优先级=" + priority);
        }
        System.err.println("║  被困建筑位置: " + buildingPosition);
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
        
        addRescueTask(buildingPosition, victimId, priority);
    }

    private void handleRescueRequest(MessagePoliceForce msg) {
        StandardEntity targetEntity = this.worldInfo.getEntity(msg.getAgentID());
        EntityID buildingPosition = msg.getPosition();
        
        if (buildingPosition == null && targetEntity instanceof Human) {
            buildingPosition = ((Human) targetEntity).getPosition();
        }
        
        if (buildingPosition == null) {
            System.err.println("[消防员分配器] ⚠️ 无法获取建筑位置，忽略警察救援请求");
            return;
        }
        
        StandardEntity buildingEntity = this.worldInfo.getEntity(buildingPosition);
        if (!(buildingEntity instanceof Building)) {
            System.err.println("[消防员分配器] ⚠️ 被困位置不是建筑: " + buildingPosition);
            return;
        }
        
        EntityID victimId = (targetEntity instanceof Human) ? targetEntity.getID() : null;
        int priority = (victimId != null) ? getTypePriorityFromEntity(victimId) : PRIORITY_TYPE_POLICE;
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [消防员分配器] 🚨 收到警察救援请求！                         ║");
        if (victimId != null) {
            System.err.println("║  被困警察 ID: " + victimId + " 优先级=" + priority);
        }
        System.err.println("║  被困建筑位置: " + buildingPosition);
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
        
        addRescueTask(buildingPosition, victimId, priority);
    }

    private void handleRescueRequest(MessageFireBrigade msg) {
        StandardEntity targetEntity = this.worldInfo.getEntity(msg.getAgentID());
        EntityID buildingPosition = msg.getPosition();
        
        if (buildingPosition == null && targetEntity instanceof Human) {
            buildingPosition = ((Human) targetEntity).getPosition();
        }
        
        if (buildingPosition == null) {
            System.err.println("[消防员分配器] ⚠️ 无法获取建筑位置，忽略消防员救援请求");
            return;
        }
        
        StandardEntity buildingEntity = this.worldInfo.getEntity(buildingPosition);
        if (!(buildingEntity instanceof Building)) {
            System.err.println("[消防员分配器] ⚠️ 被困位置不是建筑: " + buildingPosition);
            return;
        }
        
        EntityID victimId = (targetEntity instanceof Human) ? targetEntity.getID() : null;
        int priority = (victimId != null) ? getTypePriorityFromEntity(victimId) : PRIORITY_TYPE_FIRE;
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [消防员分配器] 🚨 收到消防员救援请求！                       ║");
        if (victimId != null) {
            System.err.println("║  被困消防员 ID: " + victimId + " 优先级=" + priority);
        }
        System.err.println("║  被困建筑位置: " + buildingPosition);
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
        
        addRescueTask(buildingPosition, victimId, priority);
    }

    private void handleRescueRequest(MessageAmbulanceTeam msg) {
        StandardEntity targetEntity = this.worldInfo.getEntity(msg.getAgentID());
        EntityID buildingPosition = msg.getPosition();
        
        if (buildingPosition == null && targetEntity instanceof Human) {
            buildingPosition = ((Human) targetEntity).getPosition();
        }
        
        if (buildingPosition == null) {
            System.err.println("[消防员分配器] ⚠️ 无法获取建筑位置，忽略救护车救援请求");
            return;
        }
        
        StandardEntity buildingEntity = this.worldInfo.getEntity(buildingPosition);
        if (!(buildingEntity instanceof Building)) {
            System.err.println("[消防员分配器] ⚠️ 被困位置不是建筑: " + buildingPosition);
            return;
        }
        
        EntityID victimId = (targetEntity instanceof Human) ? targetEntity.getID() : null;
        int priority = (victimId != null) ? getTypePriorityFromEntity(victimId) : PRIORITY_TYPE_AMBULANCE;
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [消防员分配器] 🚨 收到救护车救援请求！                       ║");
        if (victimId != null) {
            System.err.println("║  被困救护车 ID: " + victimId + " 优先级=" + priority);
        }
        System.err.println("║  被困建筑位置: " + buildingPosition);
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
        
        addRescueTask(buildingPosition, victimId, priority);
    }

    /**
     * 根据实体ID获取类型优先级
     */
    private int getTypePriorityFromEntity(EntityID entityId) {
        StandardEntity entity = this.worldInfo.getEntity(entityId);
        if (entity instanceof Human) {
            StandardEntityURN type = ((Human) entity).getStandardURN();
            return getTypePriority(type);
        }
        return PRIORITY_TYPE_CIVILIAN;
    }

    private int getTypePriority(StandardEntityURN type) {
        switch (type) {
            case FIRE_BRIGADE:
                return PRIORITY_TYPE_FIRE;
            case POLICE_FORCE:
                return PRIORITY_TYPE_POLICE;
            case AMBULANCE_TEAM:
                return PRIORITY_TYPE_AMBULANCE;
            case CIVILIAN:
            default:
                return PRIORITY_TYPE_CIVILIAN;
        }
    }

    /**
     * 统一添加救援任务
     * @param buildingPosition 被困建筑
     * @param victimId 被困人员ID（可能为null）
     * @param priority 任务优先级（数字越小越优先）
     */
    private void addRescueTask(EntityID buildingPosition, EntityID victimId, int priority) {
        if (completedRescues.contains(buildingPosition)) {
            System.err.println("[消防员分配器] 救援请求 " + buildingPosition + " 已完成，跳过");
            return;
        }
        
        if (victimId != null) {
            buildingToVictims.computeIfAbsent(buildingPosition, k -> new HashSet<>()).add(victimId);
            if (processedRescueMessages.contains(victimId)) {
                System.err.println("[消防员分配器] 救援请求 " + victimId + " 已处理过，跳过");
                return;
            }
            processedRescueMessages.add(victimId);
        }
        
        // 更新该建筑的优先级（取最小值，即最高优先级）
        Integer existingPriority = rescuePriorityMap.get(buildingPosition);
        if (existingPriority == null || priority < existingPriority) {
            rescuePriorityMap.put(buildingPosition, priority);
            System.err.println("[消防员分配器] 更新建筑 " + buildingPosition + " 救援优先级为 " + priority);
        }
        
        rescueRequestTargets.add(buildingPosition);
        System.err.println("[消防员分配器] ✅ 添加救援任务: 建筑=" + buildingPosition + " 优先级=" + priority);
    }

    // ==================== 灭火消息处理 ====================

    private void handleFireMessage(CommunicationMessage message) {
        if (message instanceof MessageBuilding) {
            MessageBuilding mb = (MessageBuilding) message;
            if (mb.isFierynessDefined() && mb.getFieryness() > 0) {
                EntityID buildingId = mb.getBuildingID();
                fireTargets.add(buildingId);
                System.err.println("[消防员分配器] 添加灭火任务: " + buildingId + " 优先级=" + PRIORITY_FIRE);
            }
        }
    }

    private void handleFiremanMessage(CommunicationMessage message) {
        if (message instanceof MessageFireBrigade) {
            MessageFireBrigade mfb = (MessageFireBrigade) message;
            FireInfo info = firemanMap.get(mfb.getAgentID());
            if (info == null) {
                info = new FireInfo(mfb.getAgentID());
                firemanMap.put(mfb.getAgentID(), info);
            }
            if (mfb.isPositionDefined()) {
                info.currentPosition = mfb.getPosition();
            }
            int currentTime = this.agentInfo.getTime();
            if (currentTime >= info.commandTime + 2) {
                updateFiremanInfo(info, mfb);
            }
        }
    }

    private void updateFiremanInfo(FireInfo info, MessageFireBrigade message) {
        if (message.isBuriednessDefined() && message.getBuriedness() > 0) {
            info.isBusy = false;
            info.canNewAction = false;
            if (info.currentTask != null) {
                releaseTask(info.currentTask, info.id);
                info.currentTask = null;
            }
            return;
        }

        switch (message.getAction()) {
            case MessageFireBrigade.ACTION_REST:
                info.canNewAction = true;
                info.isBusy = false;
                if (info.currentTask != null) {
                    releaseTask(info.currentTask, info.id);
                    info.currentTask = null;
                }
                break;
            case MessageFireBrigade.ACTION_MOVE:
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
            case MessageFireBrigade.ACTION_EXTINGUISH:
            case MessageFireBrigade.ACTION_RESCUE:
                info.isBusy = true;
                info.canNewAction = false;
                break;
        }
    }

    private void handleReportMessage(CommunicationMessage message) {
        if (message instanceof MessageReport) {
            MessageReport report = (MessageReport) message;
            if (report.isDone()) {
                FireInfo fireInfo = firemanMap.get(report.getSenderID());
                if (fireInfo != null && fireInfo.currentTask != null) {
                    releaseTask(fireInfo.currentTask, fireInfo.id);
                    fireInfo.currentTask = null;
                    fireInfo.isBusy = false;
                    fireInfo.canNewAction = true;
                    System.err.println("[消防员分配器] 消防员 " + report.getSenderID() + " 完成任务: " + fireInfo.currentTask);
                }
            }
        }
    }

    // ==================== 任务管理 ====================

    private void addTask(EntityID target, int priority) {
        for (Task task : taskQueue) {
            if (task.target.equals(target) && task.priority <= priority) {
                return;
            }
        }
        taskQueue.offer(new Task(target, priority, this.agentInfo.getTime()));
        System.err.println("[消防员分配器] 📋 添加任务到队列: " + target + " 优先级=" + priority);
    }

    private void updateTaskQueue() {
        int currentTime = this.agentInfo.getTime();

        // 添加救援请求任务（使用存储的优先级）
        for (EntityID target : rescueRequestTargets) {
            if (!completedRescues.contains(target)) {
                int currentCount = rescueAssignCount.getOrDefault(target, 0);
                if (currentCount < MAX_AGENTS_PER_RESCUE) {
                    Integer priority = rescuePriorityMap.get(target);
                    if (priority != null) {
                        addTask(target, priority);
                    } else {
                        addTask(target, PRIORITY_TYPE_CIVILIAN); // 默认
                    }
                }
            }
        }
        
        // 添加灭火任务
        for (EntityID target : fireTargets) {
            addTask(target, PRIORITY_FIRE);
        }

        // 移除过期任务
        List<Task> expired = new ArrayList<>();
        for (Task task : taskQueue) {
            if (currentTime - task.createTime > TASK_EXPIRE_TIME) {
                expired.add(task);
            }
        }
        taskQueue.removeAll(expired);
    }

    /**
     * 寻找最近的空闲消防员
     */
    private EntityID findBestIdleFireman(Task task) {
        EntityID bestFireman = null;
        double bestDistance = Double.MAX_VALUE;
        
        // 确定任务目标位置（建筑位置）
        EntityID taskPos = task.target;
        StandardEntity taskEntity = this.worldInfo.getEntity(task.target);
        if (taskEntity instanceof Building) {
            taskPos = task.target;
        } else if (taskEntity instanceof Human) {
            Human victim = (Human) taskEntity;
            if (victim.isPositionDefined()) {
                taskPos = victim.getPosition();
            } else {
                return null;
            }
        } else {
            return null;
        }
        
        for (Map.Entry<EntityID, FireInfo> entry : firemanMap.entrySet()) {
            EntityID firemanId = entry.getKey();
            FireInfo info = entry.getValue();
            if (info.currentTask != null) continue;      // 只考虑空闲消防员
            if (!isReachable(firemanId, taskPos)) continue;
            double distance = getDistance(firemanId, taskPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestFireman = firemanId;
            }
        }
        return bestFireman;
    }

    /**
     * 分配任务（按优先级顺序，只分配给空闲消防员）
     */
    private void assignTasks() {
        List<Task> tasks = new ArrayList<>(taskQueue);
        tasks.sort(Comparator.comparingInt(t -> t.priority));
        
        System.err.println("[消防员分配器] 开始分配任务，待分配任务数: " + tasks.size());
        for (Task task : tasks) {
            String priorityName = getPriorityName(task.priority);
            System.err.println("[消防员分配器]   任务: " + task.target + 
                               ", 优先级=" + task.priority + "(" + priorityName + ")");
        }
        
        Set<EntityID> assignedTasksSet = new HashSet<>();
        
        for (Task task : tasks) {
            if (assignedTasksSet.contains(task.target)) continue;
            
            int currentCount = rescueAssignCount.getOrDefault(task.target, 0);
            if (currentCount >= MAX_AGENTS_PER_RESCUE) {
                System.err.println("[消防员分配器] 任务 " + task.target + " 已满 (" + currentCount + "/" + MAX_AGENTS_PER_RESCUE + ")，跳过");
                taskQueue.remove(task);
                continue;
            }
            
            EntityID bestFireman = findBestIdleFireman(task);
            if (bestFireman != null) {
                assignTaskToFireman(bestFireman, task.target, task.priority);
                assignedTasksSet.add(task.target);
                rescueAssignCount.put(task.target, currentCount + 1);
                taskQueue.remove(task);
                
                String priorityName = getPriorityName(task.priority);
                System.err.println("╔══════════════════════════════════════════════════════════════╗");
                System.err.println("║  [消防员分配器] 📍 分配任务！                                 ║");
                System.err.println("║  消防员: " + bestFireman);
                System.err.println("║  任务: " + task.target + " (" + priorityName + ")");
                System.err.println("╚══════════════════════════════════════════════════════════════╝");
            }
        }
        
        // 清理已分配的任务
        rescueRequestTargets.removeAll(assignedTasksSet);
        for (EntityID target : assignedTasksSet) {
            rescuePriorityMap.remove(target);
        }
        fireTargets.removeAll(assignedTasksSet);
    }

    private String getPriorityName(int priority) {
        if (priority == PRIORITY_TYPE_FIRE) return "消防员被困";
        if (priority == PRIORITY_TYPE_POLICE) return "警察被困";
        if (priority == PRIORITY_TYPE_AMBULANCE) return "救护车被困";
        if (priority == PRIORITY_TYPE_CIVILIAN) return "平民被困";
        if (priority == PRIORITY_FIRE) return "灭火任务";
        return "普通任务";
    }

    private void assignTaskToFireman(EntityID firemanId, EntityID task, int priority) {
        assignedFiremanTasks.put(firemanId, task);
        tasksInProgress.add(task);
        FireInfo info = firemanMap.get(firemanId);
        if (info != null) {
            if (info.currentTask != null && !info.currentTask.equals(task)) {
                releaseTask(info.currentTask, info.id);
            }
            info.currentTask = task;
            info.currentTaskPriority = priority;
            info.isBusy = true;
            info.canNewAction = false;
            info.commandTime = this.agentInfo.getTime();
        }
    }

    private void releaseTask(EntityID task, EntityID agentId) {
        tasksInProgress.remove(task);
        assignedFiremanTasks.remove(agentId);
        
        int count = rescueAssignCount.getOrDefault(task, 0);
        if (count > 0) {
            rescueAssignCount.put(task, count - 1);
        }
        processedRescueMessages.remove(task);
    }

    /**
     * 检查任务完成状态
     */
    private void checkTaskCompletion() {
        for (Map.Entry<EntityID, EntityID> entry : new HashMap<>(assignedFiremanTasks).entrySet()) {
            EntityID agentId = entry.getKey();
            EntityID task = entry.getValue();
            
            StandardEntity entity = this.worldInfo.getEntity(task);
            if (entity == null) {
                releaseTask(task, agentId);
                FireInfo info = firemanMap.get(agentId);
                if (info != null) {
                    info.currentTask = null;
                    info.isBusy = false;
                    info.canNewAction = true;
                }
                continue;
            }
            
            if (entity instanceof Building) {
                Building building = (Building) entity;
                boolean hasBuriedVictim = false;
                
                // 先检查 buildingToVictims 记录
                Set<EntityID> victims = buildingToVictims.getOrDefault(task, new HashSet<>());
                List<EntityID> toRemove = new ArrayList<>();
                for (EntityID victimId : victims) {
                    StandardEntity victimEntity = this.worldInfo.getEntity(victimId);
                    if (victimEntity instanceof Human) {
                        Human victim = (Human) victimEntity;
                        if (victim.isBuriednessDefined() && victim.getBuriedness() > 0) {
                            hasBuriedVictim = true;
                            break;
                        } else {
                            toRemove.add(victimId);
                        }
                    } else {
                        toRemove.add(victimId);
                    }
                }
                victims.removeAll(toRemove);
                if (victims.isEmpty()) {
                    buildingToVictims.remove(task);
                }
                
                // 如果记录中没有，实时扫描建筑内是否有被困人员
                if (!hasBuriedVictim) {
                    Collection<StandardEntity> entitiesInBuilding = this.worldInfo.getObjectsInRange(building.getID(), 100);
                    for (StandardEntity e : entitiesInBuilding) {
                        if (e instanceof Human) {
                            Human human = (Human) e;
                            if (human.isBuriednessDefined() && human.getBuriedness() > 0 &&
                                human.isPositionDefined() && human.getPosition().equals(building.getID())) {
                                hasBuriedVictim = true;
                                break;
                            }
                        }
                    }
                }
                
                if (!hasBuriedVictim) {
                    releaseTask(task, agentId);
                    FireInfo info = firemanMap.get(agentId);
                    if (info != null) {
                        info.currentTask = null;
                        info.isBusy = false;
                        info.canNewAction = true;
                    }
                    System.err.println("[消防员分配器] ✅ 救援完成: 建筑=" + task);
                }
            } else if (entity instanceof Building) {
                // 灭火任务
                Building building = (Building) entity;
                if (!building.isOnFire()) {
                    releaseTask(task, agentId);
                    FireInfo info = firemanMap.get(agentId);
                    if (info != null) {
                        info.currentTask = null;
                        info.isBusy = false;
                        info.canNewAction = true;
                    }
                    System.err.println("[消防员分配器] ✅ 灭火完成: " + task);
                }
            } else {
                releaseTask(task, agentId);
                FireInfo info = firemanMap.get(agentId);
                if (info != null) {
                    info.currentTask = null;
                    info.isBusy = false;
                    info.canNewAction = true;
                }
                System.err.println("[消防员分配器] ✅ 任务完成: " + task);
            }
        }
    }

    /**
     * 移除已完成的任务
     */
    private void removeCompletedTasks() {
        // 救援任务
        for (EntityID buildingId : new ArrayList<>(rescueRequestTargets)) {
            StandardEntity entity = this.worldInfo.getEntity(buildingId);
            if (entity instanceof Building) {
                boolean hasBuriedVictim = false;
                
                Set<EntityID> victims = buildingToVictims.getOrDefault(buildingId, new HashSet<>());
                List<EntityID> toRemove = new ArrayList<>();
                for (EntityID victimId : victims) {
                    StandardEntity victimEntity = this.worldInfo.getEntity(victimId);
                    if (victimEntity instanceof Human) {
                        Human victim = (Human) victimEntity;
                        if (victim.isBuriednessDefined() && victim.getBuriedness() > 0) {
                            hasBuriedVictim = true;
                            break;
                        } else {
                            toRemove.add(victimId);
                        }
                    } else {
                        toRemove.add(victimId);
                    }
                }
                victims.removeAll(toRemove);
                if (victims.isEmpty()) {
                    buildingToVictims.remove(buildingId);
                }
                
                if (!hasBuriedVictim) {
                    Collection<StandardEntity> entitiesInBuilding = this.worldInfo.getObjectsInRange(buildingId, 100);
                    for (StandardEntity e : entitiesInBuilding) {
                        if (e instanceof Human) {
                            Human human = (Human) e;
                            if (human.isBuriednessDefined() && human.getBuriedness() > 0 &&
                                human.isPositionDefined() && human.getPosition().equals(buildingId)) {
                                hasBuriedVictim = true;
                                break;
                            }
                        }
                    }
                }
                
                if (!hasBuriedVictim) {
                    rescueRequestTargets.remove(buildingId);
                    completedRescues.add(buildingId);
                    rescuePriorityMap.remove(buildingId);
                    System.err.println("[消防员分配器] 📋 建筑内无被困人员，移除任务: " + buildingId);
                }
            } else {
                rescueRequestTargets.remove(buildingId);
                rescuePriorityMap.remove(buildingId);
            }
        }
        
        // 灭火任务
        for (EntityID fireId : new ArrayList<>(fireTargets)) {
            StandardEntity entity = this.worldInfo.getEntity(fireId);
            if (entity instanceof Building) {
                Building building = (Building) entity;
                if (building == null || !building.isOnFire()) {
                    fireTargets.remove(fireId);
                }
            } else {
                fireTargets.remove(fireId);
            }
        }
    }

    // ==================== 辅助方法 ====================

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

    private boolean isReachable(EntityID firemanId, EntityID targetPos) {
        if (this.pathPlanning == null) return true;
        if (targetPos == null) return false;
        
        StandardEntity agent = this.worldInfo.getEntity(firemanId);
        if (!(agent instanceof FireBrigade)) return false;
        FireBrigade fireman = (FireBrigade) agent;
        if (!fireman.isPositionDefined()) return false;
        
        List<EntityID> path = this.pathPlanning.getResult(fireman.getPosition(), targetPos);
        return path != null && path.size() > 0;
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

    private static class FireInfo {
        EntityID id;
        EntityID currentPosition;
        EntityID currentTask;
        int currentTaskPriority;
        boolean isBusy;
        boolean canNewAction;
        int commandTime;
        
        FireInfo(EntityID id) {
            this.id = id;
            this.currentTask = null;
            this.currentTaskPriority = PRIORITY_FIRE;
            this.isBusy = false;
            this.canNewAction = true;
            this.commandTime = -1;
        }
    }
}