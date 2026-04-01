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

    private static final int MAX_AGENTS_PER_RESCUE = 10;
    private static final int TASK_EXPIRE_TIME = 30;

    private PathPlanning pathPlanning;
    private Map<EntityID, FireInfo> firemanMap;
    private PriorityQueue<Task> taskQueue;
    
    private Map<EntityID, EntityID> assignedFiremanTasks;
    private Set<EntityID> tasksInProgress;
    
    private Map<EntityID, Integer> rescueAssignCount;
    private Set<EntityID> completedRescues;
    private Set<EntityID> processedRescueMessages;
    
    private Set<EntityID> rescueRequestTargets;
    private Set<EntityID> fireTargets;
    
    private Map<EntityID, Integer> rescuePriorityMap;
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

    // ==================== 位置有效性检查辅助方法 ====================
    
    /**
     * 检查人类实体是否有效（位置定义且不为null）
     */
    private boolean isValidHuman(Human human) {
        if (human == null) return false;
        if (!human.isPositionDefined()) return false;
        EntityID pos = human.getPosition();
        if (pos == null) return false;
        StandardEntity posEntity = this.worldInfo.getEntity(pos);
        if (posEntity == null) return false;
        return true;
    }
    
    /**
     * 检查建筑实体是否有效（位置定义）
     */
    private boolean isValidBuilding(Building building) {
        if (building == null) return false;
        if (!building.isXDefined() || !building.isYDefined()) return false;
        return true;
    }
    
    /**
     * 检查平民是否已被装载或已治愈
     */
    private boolean isVictimCompleted(EntityID victimId) {
        Human h = (Human) this.worldInfo.getEntity(victimId);
        if (h == null) return true;
        
        // 位置无效，说明已被装载
        if (!h.isPositionDefined()) return true;
        
        // 已死亡
        if (h.isHPDefined() && h.getHP() == 0) return true;
        
        // 已无伤害
        if (h.isDamageDefined() && h.getDamage() == 0) return true;
        
        // 在避难所
        EntityID pos = h.getPosition();
        if (pos != null) {
            StandardEntity posEntity = this.worldInfo.getEntity(pos);
            if (posEntity != null && posEntity.getStandardURN() == REFUGE) {
                return true;
            }
        }
        
        return false;
    }

    // ==================== 消息处理 ====================
    
    private void processMessages(MessageManager messageManager) {
        for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
            if (message instanceof MessageCivilian) {
                handleRescueRequest((MessageCivilian) message);
            } else if (message instanceof MessagePoliceForce) {
                handleRescueRequest((MessagePoliceForce) message);
            } else if (message instanceof MessageFireBrigade) {
                handleRescueRequest((MessageFireBrigade) message);
            } else if (message instanceof MessageAmbulanceTeam) {
                handleRescueRequest((MessageAmbulanceTeam) message);
            } else {
                handleFireMessage(message);
                handleFiremanMessage(message);
                handleReportMessage(message);
            }
        }
    }

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
        
        // ========== 检查建筑位置有效性 ==========
        StandardEntity buildingEntity = this.worldInfo.getEntity(buildingPosition);
        if (!(buildingEntity instanceof Building)) {
            return;
        }
        Building building = (Building) buildingEntity;
        if (!isValidBuilding(building)) {
            System.err.println("[消防员分配器] ⚠️ 建筑 " + buildingPosition + " 位置无效，忽略请求");
            return;
        }
        
        EntityID victimId = (targetEntity instanceof Human) ? targetEntity.getID() : null;
        
        // ========== 检查受害者是否已完成 ==========
        if (victimId != null && isVictimCompleted(victimId)) {
            return;
        }
        
        int priority = (victimId != null) ? getTypePriorityFromEntity(victimId) : PRIORITY_TYPE_CIVILIAN;
        
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
            return;
        }
        Building building = (Building) buildingEntity;
        if (!isValidBuilding(building)) {
            System.err.println("[消防员分配器] ⚠️ 建筑 " + buildingPosition + " 位置无效，忽略请求");
            return;
        }
        
        EntityID victimId = (targetEntity instanceof Human) ? targetEntity.getID() : null;
        
        if (victimId != null && isVictimCompleted(victimId)) {
            return;
        }
        
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
            return;
        }
        
        StandardEntity buildingEntity = this.worldInfo.getEntity(buildingPosition);
        if (!(buildingEntity instanceof Building)) {
            return;
        }
        Building building = (Building) buildingEntity;
        if (!isValidBuilding(building)) {
            return;
        }
        
        EntityID victimId = (targetEntity instanceof Human) ? targetEntity.getID() : null;
        
        if (victimId != null && isVictimCompleted(victimId)) {
            return;
        }
        
        int priority = (victimId != null) ? getTypePriorityFromEntity(victimId) : PRIORITY_TYPE_FIRE;
        
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
            return;
        }
        Building building = (Building) buildingEntity;
        if (!isValidBuilding(building)) {
            System.err.println("[消防员分配器] ⚠️ 建筑 " + buildingPosition + " 位置无效，忽略请求");
            return;
        }
        
        EntityID victimId = (targetEntity instanceof Human) ? targetEntity.getID() : null;
        
        if (victimId != null && isVictimCompleted(victimId)) {
            return;
        }
        
        int priority = (victimId != null) ? getTypePriorityFromEntity(victimId) : PRIORITY_TYPE_AMBULANCE;
        
        addRescueTask(buildingPosition, victimId, priority);
    }

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
            case FIRE_BRIGADE: return PRIORITY_TYPE_FIRE;
            case POLICE_FORCE: return PRIORITY_TYPE_POLICE;
            case AMBULANCE_TEAM: return PRIORITY_TYPE_AMBULANCE;
            case CIVILIAN: default: return PRIORITY_TYPE_CIVILIAN;
        }
    }

    private void addRescueTask(EntityID buildingPosition, EntityID victimId, int priority) {
        if (completedRescues.contains(buildingPosition)) {
            return;
        }
        
        if (victimId != null) {
            buildingToVictims.computeIfAbsent(buildingPosition, k -> new HashSet<>()).add(victimId);
            if (processedRescueMessages.contains(victimId)) {
                return;
            }
            processedRescueMessages.add(victimId);
        }
        
        Integer existingPriority = rescuePriorityMap.get(buildingPosition);
        if (existingPriority == null || priority < existingPriority) {
            rescuePriorityMap.put(buildingPosition, priority);
        }
        
        rescueRequestTargets.add(buildingPosition);
    }

    // ==================== 灭火消息处理 ====================

    private void handleFireMessage(CommunicationMessage message) {
        if (message instanceof MessageBuilding) {
            MessageBuilding mb = (MessageBuilding) message;
            if (mb.isFierynessDefined() && mb.getFieryness() > 0) {
                EntityID buildingId = mb.getBuildingID();
                // ========== 检查建筑有效性 ==========
                StandardEntity entity = this.worldInfo.getEntity(buildingId);
                if (entity instanceof Building) {
                    Building building = (Building) entity;
                    if (isValidBuilding(building)) {
                        fireTargets.add(buildingId);
                    }
                }
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
    }

    private void updateTaskQueue() {
        int currentTime = this.agentInfo.getTime();

        for (EntityID target : rescueRequestTargets) {
            if (!completedRescues.contains(target)) {
                int currentCount = rescueAssignCount.getOrDefault(target, 0);
                if (currentCount < MAX_AGENTS_PER_RESCUE) {
                    Integer priority = rescuePriorityMap.get(target);
                    if (priority != null) {
                        addTask(target, priority);
                    } else {
                        addTask(target, PRIORITY_TYPE_CIVILIAN);
                    }
                }
            }
        }
        
        for (EntityID target : fireTargets) {
            addTask(target, PRIORITY_FIRE);
        }

        List<Task> expired = new ArrayList<>();
        for (Task task : taskQueue) {
            if (currentTime - task.createTime > TASK_EXPIRE_TIME) {
                expired.add(task);
            }
        }
        taskQueue.removeAll(expired);
    }

    private EntityID findBestIdleFireman(Task task) {
        EntityID bestFireman = null;
        double bestDistance = Double.MAX_VALUE;
        
        EntityID taskPos = task.target;
        StandardEntity taskEntity = this.worldInfo.getEntity(task.target);
        
        if (taskEntity instanceof Building) {
            Building building = (Building) taskEntity;
            if (!isValidBuilding(building)) {
                return null;
            }
            taskPos = task.target;
        } else if (taskEntity instanceof Human) {
            Human victim = (Human) taskEntity;
            if (!isValidHuman(victim)) {
                return null;
            }
            taskPos = victim.getPosition();
        } else {
            return null;
        }
        
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

    private void assignTasks() {
        List<Task> tasks = new ArrayList<>(taskQueue);
        tasks.sort(Comparator.comparingInt(t -> t.priority));
        
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
            }
        }
        
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

    // ==================== 任务完成检查 ====================

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
                if (!isValidBuilding(building)) {
                    releaseTask(task, agentId);
                    continue;
                }
                
                boolean hasBuriedVictim = false;
                
                Set<EntityID> victims = buildingToVictims.getOrDefault(task, new HashSet<>());
                List<EntityID> toRemove = new ArrayList<>();
                for (EntityID victimId : victims) {
                    if (isVictimCompleted(victimId)) {
                        toRemove.add(victimId);
                    } else {
                        Human victim = (Human) this.worldInfo.getEntity(victimId);
                        if (victim != null && victim.isBuriednessDefined() && victim.getBuriedness() > 0) {
                            hasBuriedVictim = true;
                        }
                    }
                }
                victims.removeAll(toRemove);
                if (victims.isEmpty()) {
                    buildingToVictims.remove(task);
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
                Building building = (Building) entity;
                if (isValidBuilding(building) && !building.isOnFire()) {
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

    private void removeCompletedTasks() {
        // 救援任务
        for (EntityID buildingId : new ArrayList<>(rescueRequestTargets)) {
            StandardEntity entity = this.worldInfo.getEntity(buildingId);
            if (entity instanceof Building) {
                Building building = (Building) entity;
                if (!isValidBuilding(building)) {
                    rescueRequestTargets.remove(buildingId);
                    rescuePriorityMap.remove(buildingId);
                    continue;
                }
                
                boolean hasBuriedVictim = false;
                
                Set<EntityID> victims = buildingToVictims.getOrDefault(buildingId, new HashSet<>());
                List<EntityID> toRemove = new ArrayList<>();
                for (EntityID victimId : victims) {
                    if (isVictimCompleted(victimId)) {
                        toRemove.add(victimId);
                    } else {
                        Human victim = (Human) this.worldInfo.getEntity(victimId);
                        if (victim != null && victim.isBuriednessDefined() && victim.getBuriedness() > 0) {
                            hasBuriedVictim = true;
                        }
                    }
                }
                victims.removeAll(toRemove);
                if (victims.isEmpty()) {
                    buildingToVictims.remove(buildingId);
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
                if (!isValidBuilding(building) || !building.isOnFire()) {
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