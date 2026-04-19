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
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;

public class FireTargetAllocator extends adf.core.component.module.complex.FireTargetAllocator {

    private static final int PRIORITY_TYPE_FIRE = 0;
    private static final int PRIORITY_TYPE_POLICE = 1;
    private static final int PRIORITY_TYPE_AMBULANCE = 2;
    private static final int PRIORITY_TYPE_CIVILIAN = 3;
    private static final int PRIORITY_FIRE = 4;

    private static final int MAX_AGENTS_PER_RESCUE = 2;
    private static final int TASK_EXPIRE_TIME = 50;

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

    private Set<EntityID> targetsLockedByOthers;
    private Map<EntityID, Integer> targetRescueCount;

    private Set<String> loggedMessageIds;
    private int lastLogCleanTime;

    // ========== 关键修复：记录本轮新分配的任务，确保 getResult 不返回空 ==========
    private Map<EntityID, EntityID> newAssignments;

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
        
        this.targetsLockedByOthers = new HashSet<>();
        this.targetRescueCount = new HashMap<>();
        
        this.loggedMessageIds = new HashSet<>();
        this.lastLogCleanTime = 0;
        this.newAssignments = new HashMap<>();

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
        
        //System.err.println("[消防员分配器] 已加载 ");
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
        // 优先返回本轮新分配的任务，避免因 isBusy 状态导致的空结果
        if (!newAssignments.isEmpty()) {
            // ========== 新增：返回前过滤已完成任务 ==========
            Map<EntityID, EntityID> validAssignments = new HashMap<>();
            for (Map.Entry<EntityID, EntityID> entry : newAssignments.entrySet()) {
                EntityID target = entry.getValue();
                if (!isVictimCompleted(target)) {
                    validAssignments.put(entry.getKey(), entry.getValue());
                } else {
                    // 任务已完成，释放消防车
                    releaseTask(target, entry.getKey());
                    FireInfo info = firemanMap.get(entry.getKey());
                    if (info != null) {
                        info.currentTask = null;
                        info.isBusy = false;
                    }
                }
            }
            newAssignments = validAssignments;
            //System.err.println("[消防员分配器] getResult() 返回本轮新分配任务数: " + newAssignments.size());
            return new HashMap<>(newAssignments);
        }
        // 兼容旧逻辑（通常不会执行到这里）
        Map<EntityID, EntityID> result = new HashMap<>();
        for (Map.Entry<EntityID, EntityID> entry : assignedFiremanTasks.entrySet()) {
            FireInfo info = firemanMap.get(entry.getKey());
            if (info != null && !info.isBusy) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        //System.err.println("[消防员分配器] getResult() 返回任务数: " + result.size());
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
        
        int currentTime = this.agentInfo.getTime();
        if (currentTime - lastLogCleanTime > 20) {
            lastLogCleanTime = currentTime;
            loggedMessageIds.clear();
        }
        
        // 清理已失效的锁定目标（修复 ClassCastException：先判断类型）
        targetsLockedByOthers.removeIf(vid -> {
            StandardEntity e = worldInfo.getEntity(vid);
            if (!(e instanceof Human)) return true; // 非人员实体直接移除
            Human h = (Human) e;
            return (h.isHPDefined() && h.getHP() == 0) ||
                   (h.isBuriednessDefined() && h.getBuriedness() == 0);
        });
        targetRescueCount.keySet().removeIf(vid -> !targetsLockedByOthers.contains(vid));
        
        scanWorldForBuriedVictims();
        processMessages(messageManager);
        removeCompletedTasks();
        checkTaskCompletion();
        
        return this;
    }

    private void initialize() {
        for (EntityID id : this.worldInfo.getEntityIDsOfType(FIRE_BRIGADE)) {
            firemanMap.put(id, new FireInfo(id));
        }
        //System.err.println("[消防员分配器] 初始化完成，消防员数量: " + firemanMap.size());
    }

    private void scanWorldForBuriedVictims() {
        int found = 0;
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(
                CIVILIAN, FIRE_BRIGADE, POLICE_FORCE, AMBULANCE_TEAM)) {
            Human h = (Human) e;
            EntityID victimId = h.getID();
            
            if (isVictimCompleted(victimId)) continue;
            
            if (h.isBuriednessDefined() && h.getBuriedness() > 0) {
                if (!processedRescueMessages.contains(victimId)) {
                    processedRescueMessages.add(victimId);
                    rescueRequestTargets.add(victimId);
                    int priority = getTypePriority(h.getStandardURN());
                    rescuePriorityMap.put(victimId, priority);
                    found++;
                }
            }
        }
        
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(BUILDING, GAS_STATION)) {
            Building b = (Building) e;
            if (b.isOnFire()) {
                fireTargets.add(b.getID());
            }
        }
        
        if (found > 0) {
            //System.err.println("[消防员分配器] 🔍 主动扫描发现 " + found + " 个被掩埋单位");
        }
    }

    private boolean isVictimCompleted(EntityID victimId) {
        Human h = (Human) this.worldInfo.getEntity(victimId);
        if (h == null) return true;
        if (h.isHPDefined() && h.getHP() == 0) return true;
        if (h.isBuriednessDefined() && h.getBuriedness() == 0) return true;
        if (!h.isPositionDefined()) return true;
        return false;
    }

    private int getTypePriority(StandardEntityURN type) {
        switch (type) {
            case FIRE_BRIGADE: return PRIORITY_TYPE_FIRE;
            case POLICE_FORCE: return PRIORITY_TYPE_POLICE;
            case AMBULANCE_TEAM: return PRIORITY_TYPE_AMBULANCE;
            case CIVILIAN: default: return PRIORITY_TYPE_CIVILIAN;
        }
    }

    private void processMessages(MessageManager messageManager) {
        List<CommunicationMessage> messages = messageManager.getReceivedMessageList();
        
        for (CommunicationMessage message : messages) {
            if (message instanceof MessageCivilian) {
                MessageCivilian mc = (MessageCivilian) message;
                MessageUtil.reflectMessage(this.worldInfo, mc);
                handleRescueRequest(mc);
            } else if (message instanceof MessagePoliceForce) {
                MessagePoliceForce mpf = (MessagePoliceForce) message;
                MessageUtil.reflectMessage(this.worldInfo, mpf);
                handleRescueRequest(mpf);
            } else if (message instanceof MessageFireBrigade) {
                MessageFireBrigade mfb = (MessageFireBrigade) message;
                MessageUtil.reflectMessage(this.worldInfo, mfb);
                handleRescueRequest(mfb);
                handleFiremanMessage(mfb);
                
                if (mfb.isRadio() && mfb.getAction() == MessageFireBrigade.ACTION_RESCUE 
                    && mfb.getTargetID() != null) {
                    EntityID target = mfb.getTargetID();
                    if (!mfb.getAgentID().equals(this.agentInfo.getID())) {
                        targetsLockedByOthers.add(target);
                        targetRescueCount.put(target, targetRescueCount.getOrDefault(target, 0) + 1);
                    }
                }
            } else if (message instanceof MessageAmbulanceTeam) {
                MessageAmbulanceTeam mat = (MessageAmbulanceTeam) message;
                MessageUtil.reflectMessage(this.worldInfo, mat);
                handleRescueRequest(mat);
            } else if (message instanceof MessageBuilding) {
                MessageBuilding mb = (MessageBuilding) message;
                MessageUtil.reflectMessage(this.worldInfo, mb);
                handleFireMessage(mb);
            } else if (message instanceof MessageReport) {
                handleReportMessage((MessageReport) message);
            }
        }
    }

    private void handleRescueRequest(MessageCivilian msg) {
        EntityID victimId = msg.getAgentID();
        
        // ========== 新增：如果平民已挖出，立即清理任务 ==========
        if (msg.isBuriednessDefined() && msg.getBuriedness() == 0) {
            // 平民已挖出，从所有任务集合中移除
            if (rescueRequestTargets.remove(victimId)) {
                completedRescues.add(victimId);
                rescuePriorityMap.remove(victimId);
                processedRescueMessages.remove(victimId);
                // 释放所有分配到该目标的消防车
                for (Map.Entry<EntityID, EntityID> entry : new HashMap<>(assignedFiremanTasks).entrySet()) {
                    if (entry.getValue().equals(victimId)) {
                        releaseTask(victimId, entry.getKey());
                        FireInfo info = firemanMap.get(entry.getKey());
                        if (info != null) {
                            info.currentTask = null;
                            info.isBusy = false;
                        }
                    }
                }
                //System.err.println("[消防员分配器] 收到挖出消息，清理已完成任务: " + victimId);
            }
            return;
        }
        // ==================================================
        
        if (msg.isHPDefined() && msg.getHP() == 0) return;
        if (!msg.isBuriednessDefined() || msg.getBuriedness() == 0) return;
        if (!msg.isPositionDefined()) return;
        
        if (!processedRescueMessages.contains(victimId)) {
            processedRescueMessages.add(victimId);
            rescueRequestTargets.add(victimId);
            rescuePriorityMap.put(victimId, PRIORITY_TYPE_CIVILIAN);
            //System.err.println("[消防员分配器] ✅ 添加平民救援任务: " + victimId);
        }
    }

    private void handleRescueRequest(MessagePoliceForce msg) {
        EntityID victimId = msg.getAgentID();
        if (msg.isHPDefined() && msg.getHP() == 0) return;
        if (!msg.isBuriednessDefined() || msg.getBuriedness() == 0) return;
        if (!msg.isPositionDefined()) return;
        
        if (!processedRescueMessages.contains(victimId)) {
            processedRescueMessages.add(victimId);
            rescueRequestTargets.add(victimId);
            rescuePriorityMap.put(victimId, PRIORITY_TYPE_POLICE);
        }
    }

    private void handleRescueRequest(MessageFireBrigade msg) {
        EntityID victimId = msg.getAgentID();
        if (msg.isHPDefined() && msg.getHP() == 0) return;
        if (!msg.isBuriednessDefined() || msg.getBuriedness() == 0) return;
        if (!msg.isPositionDefined()) return;
        
        if (!processedRescueMessages.contains(victimId)) {
            processedRescueMessages.add(victimId);
            rescueRequestTargets.add(victimId);
            rescuePriorityMap.put(victimId, PRIORITY_TYPE_FIRE);
        }
    }

    private void handleRescueRequest(MessageAmbulanceTeam msg) {
        EntityID victimId = msg.getAgentID();
        if (msg.isHPDefined() && msg.getHP() == 0) return;
        if (!msg.isBuriednessDefined() || msg.getBuriedness() == 0) return;
        if (!msg.isPositionDefined()) return;
        
        if (!processedRescueMessages.contains(victimId)) {
            processedRescueMessages.add(victimId);
            rescueRequestTargets.add(victimId);
            rescuePriorityMap.put(victimId, PRIORITY_TYPE_AMBULANCE);
        }
    }

    private void handleFireMessage(MessageBuilding msg) {
        if (msg.isFierynessDefined() && msg.getFieryness() > 0) {
            fireTargets.add(msg.getBuildingID());
        }
    }

    private void handleFiremanMessage(MessageFireBrigade mfb) {
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

    private void updateFiremanInfo(FireInfo info, MessageFireBrigade message) {
        if (message.isBuriednessDefined() && message.getBuriedness() > 0) {
            info.isBusy = false;
            if (info.currentTask != null) {
                releaseTask(info.currentTask, info.id);
                info.currentTask = null;
            }
            return;
        }

        switch (message.getAction()) {
            case MessageFireBrigade.ACTION_REST:
                info.isBusy = false;
                if (info.currentTask != null) {
                    releaseTask(info.currentTask, info.id);
                    info.currentTask = null;
                }
                break;
            case MessageFireBrigade.ACTION_MOVE:
                if (message.getTargetID() != null && message.getTargetID().equals(info.currentTask)) {
                    info.isBusy = true;
                }
                break;
            case MessageFireBrigade.ACTION_RESCUE:
            case MessageFireBrigade.ACTION_EXTINGUISH:
                info.isBusy = true;
                break;
        }
    }

    private void handleReportMessage(MessageReport report) {
        if (report.isDone()) {
            FireInfo info = firemanMap.get(report.getSenderID());
            if (info != null && info.currentTask != null) {
                releaseTask(info.currentTask, info.id);
                info.currentTask = null;
                info.isBusy = false;
            }
        }
    }

    private void updateTaskQueue() {
        int currentTime = this.agentInfo.getTime();
        
        for (EntityID target : rescueRequestTargets) {
            if (!completedRescues.contains(target)) {
                int lockedCount = targetRescueCount.getOrDefault(target, 0);
                int assignedCount = rescueAssignCount.getOrDefault(target, 0);
                int totalCount = Math.max(lockedCount, assignedCount);
                if (totalCount < MAX_AGENTS_PER_RESCUE) {
                    Integer priority = rescuePriorityMap.getOrDefault(target, PRIORITY_TYPE_CIVILIAN);
                    addTask(target, priority);
                }
            }
        }
        
        for (EntityID target : fireTargets) {
            addTask(target, PRIORITY_FIRE);
        }
        
        taskQueue.removeIf(t -> currentTime - t.createTime > TASK_EXPIRE_TIME);
    }

    private void addTask(EntityID target, int priority) {
        for (Task t : taskQueue) {
            if (t.target.equals(target) && t.priority <= priority) return;
        }
        taskQueue.offer(new Task(target, priority, this.agentInfo.getTime()));
    }

    private void assignTasks() {
        newAssignments.clear(); // 清空上一轮记录
        
        List<EntityID> idleFiremen = new ArrayList<>();
        for (Map.Entry<EntityID, FireInfo> entry : firemanMap.entrySet()) {
            if (entry.getValue().currentTask == null) {
                idleFiremen.add(entry.getKey());
            }
        }
        
        if (idleFiremen.isEmpty()) {
            return;
        }
        
        List<Task> tasks = new ArrayList<>(taskQueue);
        tasks.sort(Comparator.comparingInt(t -> t.priority));
        
        Map<EntityID, Integer> roundAssignCount = new HashMap<>();
        
        for (EntityID firemanId : idleFiremen) {
            Task selectedTask = null;
            for (Task task : tasks) {
                EntityID target = task.target;
                
                // ========== 新增：分配前再次检查目标是否有效 ==========
                if (isVictimCompleted(target)) {
                    completedRescues.add(target);
                    rescueRequestTargets.remove(target);
                    continue;
                }
                // ==============================================
                
                int lockedCount = targetRescueCount.getOrDefault(target, 0);
                int assignedCount = rescueAssignCount.getOrDefault(target, 0);
                int roundCount = roundAssignCount.getOrDefault(target, 0);
                int totalCount = Math.max(lockedCount, assignedCount) + roundCount;
                
                if (totalCount >= MAX_AGENTS_PER_RESCUE) {
                    continue;
                }
                
                selectedTask = task;
                break;
            }
            
            if (selectedTask == null) {
                for (EntityID target : rescueRequestTargets) {
                    if (isVictimCompleted(target)) {
                        completedRescues.add(target);
                        continue;
                    }
                    int lockedCount = targetRescueCount.getOrDefault(target, 0);
                    int assignedCount = rescueAssignCount.getOrDefault(target, 0);
                    int roundCount = roundAssignCount.getOrDefault(target, 0);
                    int totalCount = Math.max(lockedCount, assignedCount) + roundCount;
                    if (totalCount < MAX_AGENTS_PER_RESCUE) {
                        selectedTask = new Task(target, rescuePriorityMap.getOrDefault(target, PRIORITY_TYPE_CIVILIAN), 0);
                        break;
                    }
                }
            }
            
            if (selectedTask != null) {
                EntityID target = selectedTask.target;
                assignTaskToFireman(firemanId, target, selectedTask.priority);
                roundAssignCount.put(target, roundAssignCount.getOrDefault(target, 0) + 1);
                rescueAssignCount.put(target, rescueAssignCount.getOrDefault(target, 0) + 1);
                newAssignments.put(firemanId, target);  // 记录本轮新分配
                
                /*System.err.println("[消防员分配器] ✅ 分配救援任务: 消防车=" + firemanId + 
                                   " 目标=" + target + " (当前参与数=" + 
                                   (targetRescueCount.getOrDefault(target, 0) + roundAssignCount.get(target)) + 
                                   "/" + MAX_AGENTS_PER_RESCUE + ")");*/
            }
        }
        
        rescueRequestTargets.removeIf(target -> {
            if (isVictimCompleted(target)) {
                completedRescues.add(target);
                return true;
            }
            int lockedCount = targetRescueCount.getOrDefault(target, 0);
            int assignedCount = rescueAssignCount.getOrDefault(target, 0);
            return Math.max(lockedCount, assignedCount) >= MAX_AGENTS_PER_RESCUE;
        });
        
        fireTargets.removeIf(id -> {
            StandardEntity e = worldInfo.getEntity(id);
            return !(e instanceof Building) || !((Building) e).isOnFire();
        });
        
        System.err.printf("[消防分配器] 时间=%d 队列: 救援=%d 灭火=%d 已分配消防车=%d%n",
                this.agentInfo.getTime(), rescueRequestTargets.size(), fireTargets.size(), assignedFiremanTasks.size());
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
    }

    private void checkTaskCompletion() {
        for (Map.Entry<EntityID, EntityID> entry : new HashMap<>(assignedFiremanTasks).entrySet()) {
            EntityID agentId = entry.getKey();
            EntityID task = entry.getValue();
            if (isVictimCompleted(task)) {
                releaseTask(task, agentId);
                FireInfo info = firemanMap.get(agentId);
                if (info != null) {
                    info.currentTask = null;
                    info.isBusy = false;
                }
                completedRescues.add(task);
            }
        }
        
        for (Map.Entry<EntityID, EntityID> entry : new HashMap<>(assignedFiremanTasks).entrySet()) {
            EntityID agentId = entry.getKey();
            EntityID task = entry.getValue();
            StandardEntity e = worldInfo.getEntity(task);
            if (e instanceof Building) {
                Building b = (Building) e;
                if (!b.isOnFire()) {
                    releaseTask(task, agentId);
                    FireInfo info = firemanMap.get(agentId);
                    if (info != null) {
                        info.currentTask = null;
                        info.isBusy = false;
                    }
                }
            }
        }
    }

    private void removeCompletedTasks() {
        rescueRequestTargets.removeIf(this::isVictimCompleted);
        fireTargets.removeIf(id -> {
            StandardEntity e = worldInfo.getEntity(id);
            return !(e instanceof Building) || !((Building) e).isOnFire();
        });
    }

    private double getDistance(EntityID from, EntityID to) {
        if (pathPlanning != null) {
            return pathPlanning.getDistance(from, to);
        }
        return worldInfo.getDistance(from, to);
    }

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
        int commandTime;
        
        FireInfo(EntityID id) {
            this.id = id;
            this.currentTask = null;
            this.currentTaskPriority = PRIORITY_FIRE;
            this.isBusy = false;
            this.commandTime = -1;
        }
    }
}