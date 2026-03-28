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

    // 优先级常量
    private static final int PRIORITY_RESCUE_REQUEST = 0;    // 救援请求（从其他单位来的，最高优先级）
    private static final int PRIORITY_FIRE = 1;              // 灭火任务
    private static final int PRIORITY_EXPLORE = 2;           // 探索任务

    private static final int MAX_AGENTS_PER_RESCUE = 5;       // 每个救援请求最多分配的消防员数量
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
    private Set<EntityID> fireTargets;
    private Set<EntityID> exploreTargets;
    private Set<EntityID> exploredBuildings;
    
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
        this.exploreTargets = new HashSet<>();
        this.exploredBuildings = new HashSet<>();
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
        System.err.println("║  优先级: 救援请求(0) > 灭火(1) > 探索(2)                       ║");
        System.err.println("║  策略: 空闲 > 打断低优先级任务 > 距离优先                       ║");
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
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(BUILDING, GAS_STATION,
                AMBULANCE_CENTRE, FIRE_STATION, POLICE_OFFICE)) {
            if (e.getStandardURN() != REFUGE) {
                exploreTargets.add(e.getID());
            }
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
                handleExploreMessage(message);
            }
        }
    }

    // ==================== 救援请求处理（支持所有类型） ====================

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
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [消防员分配器] 🚨 收到平民救援请求！                          ║");
        if (victimId != null) {
            System.err.println("║  被困平民 ID: " + victimId);
        }
        System.err.println("║  被困建筑位置: " + buildingPosition);
        System.err.println("║  优先级: " + PRIORITY_RESCUE_REQUEST + " (最高优先级)");
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
        
        addRescueTask(buildingPosition, victimId);
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
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [消防员分配器] 🚨 收到警察救援请求！                         ║");
        if (victimId != null) {
            System.err.println("║  被困警察 ID: " + victimId);
        }
        System.err.println("║  被困建筑位置: " + buildingPosition);
        System.err.println("║  优先级: " + PRIORITY_RESCUE_REQUEST + " (最高优先级)");
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
        
        addRescueTask(buildingPosition, victimId);
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
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [消防员分配器] 🚨 收到消防员救援请求！                       ║");
        if (victimId != null) {
            System.err.println("║  被困消防员 ID: " + victimId);
        }
        System.err.println("║  被困建筑位置: " + buildingPosition);
        System.err.println("║  优先级: " + PRIORITY_RESCUE_REQUEST + " (最高优先级)");
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
        
        addRescueTask(buildingPosition, victimId);
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
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [消防员分配器] 🚨 收到救护车救援请求！                       ║");
        if (victimId != null) {
            System.err.println("║  被困救护车 ID: " + victimId);
        }
        System.err.println("║  被困建筑位置: " + buildingPosition);
        System.err.println("║  优先级: " + PRIORITY_RESCUE_REQUEST + " (最高优先级)");
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
        
        addRescueTask(buildingPosition, victimId);
    }

    /**
     * 统一添加救援任务
     */
    private void addRescueTask(EntityID buildingPosition, EntityID victimId) {
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
        
        rescueRequestTargets.add(buildingPosition);
        addTask(buildingPosition, PRIORITY_RESCUE_REQUEST);
        System.err.println("[消防员分配器] ✅ 添加救援任务: 建筑=" + buildingPosition);
    }

    // ==================== 其他消息处理 ====================

    private void handleFireMessage(CommunicationMessage message) {
        if (message instanceof MessageBuilding) {
            MessageBuilding mb = (MessageBuilding) message;
            if (mb.isFierynessDefined() && mb.getFieryness() > 0) {
                EntityID buildingId = mb.getBuildingID();
                fireTargets.add(buildingId);
                exploreTargets.remove(buildingId);
                addTask(buildingId, PRIORITY_FIRE);
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

    private void handleExploreMessage(CommunicationMessage message) {
        if (message instanceof MessageBuilding) {
            MessageBuilding mb = (MessageBuilding) message;
            exploredBuildings.add(mb.getBuildingID());
            exploreTargets.remove(mb.getBuildingID());
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
    }

    private void updateTaskQueue() {
        int currentTime = this.agentInfo.getTime();

        // 添加救援请求任务
        for (EntityID target : rescueRequestTargets) {
            if (!completedRescues.contains(target)) {
                int currentCount = rescueAssignCount.getOrDefault(target, 0);
                if (currentCount < MAX_AGENTS_PER_RESCUE) {
                    addTask(target, PRIORITY_RESCUE_REQUEST);
                }
            }
        }
        
        // 添加灭火任务
        for (EntityID target : fireTargets) {
            addTask(target, PRIORITY_FIRE);
        }
        
        // 添加探索任务
        for (EntityID target : exploreTargets) {
            if (!exploredBuildings.contains(target)) {
                addTask(target, PRIORITY_EXPLORE);
            }
        }

        List<Task> expired = new ArrayList<>();
        for (Task task : taskQueue) {
            if (currentTime - task.createTime > TASK_EXPIRE_TIME) {
                expired.add(task);
            }
        }
        taskQueue.removeAll(expired);
    }

    /**
     * 为救援请求任务寻找最佳消防员（目标是建筑）
     */
    private EntityID findBestFiremanForRescue(Task task) {
        EntityID bestFireman = null;
        double bestDistance = Double.MAX_VALUE;
        FireInfo bestInfo = null;
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [消防员分配器] 🔍 为救援请求寻找消防员                       ║");
        System.err.println("║  目标建筑: " + task.target);
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
        
        EntityID buildingPos = task.target;
        StandardEntity buildingEntity = this.worldInfo.getEntity(buildingPos);
        if (!(buildingEntity instanceof Building)) {
            System.err.println("[消防员分配器] ❌ 任务目标 " + buildingPos + " 不是建筑");
            return null;
        }
        
        int idleCount = 0, reachableIdleCount = 0;
        int busyCount = 0, reachableBusyCount = 0;
        
        // 第一轮：找空闲的消防员
        for (Map.Entry<EntityID, FireInfo> entry : firemanMap.entrySet()) {
            EntityID firemanId = entry.getKey();
            FireInfo info = entry.getValue();
            
            if (info.currentTask == null) {
                idleCount++;
                boolean reachable = isReachable(firemanId, buildingPos);
                System.err.println("[消防员分配器] 空闲消防员 " + firemanId + ", 可达=" + reachable);
                if (reachable) {
                    reachableIdleCount++;
                    double distance = getDistance(firemanId, buildingPos);
                    System.err.println("[消防员分配器]   距离=" + (int)distance);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestFireman = firemanId;
                        bestInfo = info;
                    }
                }
            } else {
                busyCount++;
                System.err.println("[消防员分配器] 忙碌消防员 " + firemanId + 
                                   ", 当前任务=" + info.currentTask + 
                                   ", 优先级=" + info.currentTaskPriority);
            }
        }
        
        System.err.println("[消防员分配器] 统计: 空闲=" + idleCount + ", 忙碌=" + busyCount + 
                           ", 可达空闲=" + reachableIdleCount);
        
        if (bestFireman != null) {
            System.err.println("[消防员分配器] ✅ 分配空闲消防员: " + bestFireman + 
                               " 距离=" + (int)bestDistance);
            return bestFireman;
        }
        
        // 第二轮：没有空闲，找可以打断的
        System.err.println("[消防员分配器] ⚠️ 无空闲消防员，尝试打断低优先级任务");
        bestDistance = Double.MAX_VALUE;
        int skippedByPriorityCount = 0;
        
        for (Map.Entry<EntityID, FireInfo> entry : firemanMap.entrySet()) {
            EntityID firemanId = entry.getKey();
            FireInfo info = entry.getValue();
            if (info.currentTask == null) continue;
            
            boolean canInterrupt = (info.currentTaskPriority > PRIORITY_RESCUE_REQUEST);
            System.err.println("[消防员分配器] 检查消防员 " + firemanId + 
                               ": 当前任务优先级=" + info.currentTaskPriority + 
                               ", 可打断=" + canInterrupt);
            if (!canInterrupt) {
                skippedByPriorityCount++;
                System.err.println("[消防员分配器]   ⏭️ 跳过: 优先级 " + info.currentTaskPriority + 
                                   " 不高于 " + PRIORITY_RESCUE_REQUEST);
                continue;
            }
            
            boolean reachable = isReachable(firemanId, buildingPos);
            System.err.println("[消防员分配器]   可达=" + reachable);
            if (reachable) {
                reachableBusyCount++;
                double distance = getDistance(firemanId, buildingPos);
                System.err.println("[消防员分配器]   距离=" + (int)distance);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestFireman = firemanId;
                    bestInfo = info;
                }
            }
        }
        
        System.err.println("[消防员分配器] 统计: 忙碌=" + busyCount + 
                           ", 因优先级跳过=" + skippedByPriorityCount +
                           ", 可达忙碌=" + reachableBusyCount);
        
        if (bestFireman != null) {
            System.err.println("╔══════════════════════════════════════════════════════════════╗");
            System.err.println("║  [消防员分配器] 🔥 打断消防员 " + bestFireman + " 的任务！        ║");
            System.err.println("║  原任务: " + bestInfo.currentTask + 
                               " (优先级=" + bestInfo.currentTaskPriority + ")");
            System.err.println("║  新任务: 建筑=" + task.target + 
                               " (优先级=" + task.priority + ")");
            System.err.println("║  距离: " + (int)bestDistance);
            System.err.println("╚══════════════════════════════════════════════════════════════╝");
            return bestFireman;
        }
        
        System.err.println("[消防员分配器] ❌ 无法找到合适的消防员执行任务: 建筑=" + task.target);
        return null;
    }

    /**
     * 找最近的空闲消防员（用于低优先级任务）
     */
    private EntityID findBestIdleFireman(Task task) {
        EntityID bestFireman = null;
        double bestDistance = Double.MAX_VALUE;
        
        EntityID taskPos = null;
        StandardEntity taskEntity = this.worldInfo.getEntity(task.target);
        if (taskEntity instanceof Building) {
            taskPos = task.target;
        } else if (taskEntity instanceof Human) {
            Human victim = (Human) taskEntity;
            if (victim.isPositionDefined()) {
                taskPos = victim.getPosition();
            }
        }
        
        if (taskPos == null) return null;
        
        for (Map.Entry<EntityID, FireInfo> entry : firemanMap.entrySet()) {
            EntityID firemanId = entry.getKey();
            FireInfo info = entry.getValue();
            if (info.currentTask != null) continue;
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
     * 分配任务
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
            
            EntityID bestFireman = null;
            if (task.priority == PRIORITY_RESCUE_REQUEST) {
                bestFireman = findBestFiremanForRescue(task);
            } else {
                bestFireman = findBestIdleFireman(task);
            }
            
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
        fireTargets.removeAll(assignedTasksSet);
        exploreTargets.removeAll(assignedTasksSet);
    }

    private String getPriorityName(int priority) {
        switch (priority) {
            case PRIORITY_RESCUE_REQUEST: return "救援请求";
            case PRIORITY_FIRE: return "灭火任务";
            case PRIORITY_EXPLORE: return "探索任务";
            default: return "普通任务";
        }
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
            } else if (entity instanceof Human) {
                // 兼容旧逻辑
                Human human = (Human) entity;
                if ((human.isHPDefined() && human.getHP() == 0) ||
                    (human.isBuriednessDefined() && human.getBuriedness() == 0)) {
                    releaseTask(task, agentId);
                    FireInfo info = firemanMap.get(agentId);
                    if (info != null) {
                        info.currentTask = null;
                        info.isBusy = false;
                        info.canNewAction = true;
                    }
                    System.err.println("[消防员分配器] ✅ 救援完成: " + task);
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
                    System.err.println("[消防员分配器] 📋 建筑内无被困人员，移除任务: " + buildingId);
                }
            } else {
                rescueRequestTargets.remove(buildingId);
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
        
        exploreTargets.removeAll(exploredBuildings);
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
            this.currentTaskPriority = PRIORITY_EXPLORE;
            this.isBusy = false;
            this.canNewAction = true;
            this.commandTime = -1;
        }
    }
}