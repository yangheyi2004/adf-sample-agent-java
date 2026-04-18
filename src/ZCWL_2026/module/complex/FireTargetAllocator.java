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

    // 优先级常量
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
    private Set<EntityID> processedRescueMessages; // 已处理过的救援请求（防止重复添加）
    
    private Set<EntityID> rescueRequestTargets;  // 人员ID
    private Set<EntityID> fireTargets;           // 建筑ID
    
    private Map<EntityID, Integer> rescuePriorityMap;

    // 日志去重
    private Set<String> loggedMessageIds;
    private int lastLogCleanTime;

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
        
        this.loggedMessageIds = new HashSet<>();
        this.lastLogCleanTime = 0;

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
        
        System.err.println("[消防员分配器] 已加载 - 直接使用消息数据，绕过世界模型");
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
        
        int currentTime = this.agentInfo.getTime();
        if (currentTime - lastLogCleanTime > 20) {
            lastLogCleanTime = currentTime;
            loggedMessageIds.clear();
        }
        
        // 主动扫描世界（作为保底和任务完成检查）
        scanWorldForBuriedVictims();
        
        // 处理消息（直接基于消息内容添加任务）
        processMessages(messageManager);
        
        // 清理已完成任务
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

    // ========== 主动扫描世界（用于任务完成检查和补充遗漏） ==========
    private void scanWorldForBuriedVictims() {
        int found = 0;
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(
                CIVILIAN, FIRE_BRIGADE, POLICE_FORCE, AMBULANCE_TEAM)) {
            Human h = (Human) e;
            EntityID victimId = h.getID();
            
            // 如果任务已完成，跳过
            if (isVictimCompleted(victimId)) {
                // 如果曾经处理过，则标记完成
                if (processedRescueMessages.contains(victimId)) {
                    completedRescues.add(victimId);
                }
                continue;
            }
            
            // 如果被掩埋且尚未处理，作为补充任务加入
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
        
        // 扫描着火建筑
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(BUILDING, GAS_STATION)) {
            Building b = (Building) e;
            if (b.isOnFire()) {
                fireTargets.add(b.getID());
            }
        }
        
        if (found > 0) {
            System.err.println("[消防员分配器] 🔍 主动扫描补充发现 " + found + " 个被掩埋单位");
        }
    }

    // 基于世界模型判断任务是否已完成（用于清理）
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

    // ========== 消息处理（关键修复：直接使用消息数据，不依赖世界模型） ==========
    private void processMessages(MessageManager messageManager) {
        List<CommunicationMessage> messages = messageManager.getReceivedMessageList();
        
        int civilianCount = 0, policeCount = 0, fireCount = 0, ambulanceCount = 0;
        
        for (CommunicationMessage message : messages) {
            if (message instanceof MessageCivilian) {
                civilianCount++;
                MessageCivilian mc = (MessageCivilian) message;
                MessageUtil.reflectMessage(this.worldInfo, mc); // 同步世界模型（不影响判断）
                handleRescueRequest(mc);
            } else if (message instanceof MessagePoliceForce) {
                policeCount++;
                MessagePoliceForce mpf = (MessagePoliceForce) message;
                MessageUtil.reflectMessage(this.worldInfo, mpf);
                handleRescueRequest(mpf);
            } else if (message instanceof MessageFireBrigade) {
                fireCount++;
                MessageFireBrigade mfb = (MessageFireBrigade) message;
                MessageUtil.reflectMessage(this.worldInfo, mfb);
                handleRescueRequest(mfb);
                handleFiremanMessage(mfb);
            } else if (message instanceof MessageAmbulanceTeam) {
                ambulanceCount++;
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
        
        if (civilianCount > 0 || policeCount > 0 || fireCount > 0 || ambulanceCount > 0) {
            /*System.err.println("[消防员分配器] 📨 本轮消息: 平民=" + civilianCount + 
                               " 警察=" + policeCount + " 消防=" + fireCount + " 救护=" + ambulanceCount);*/
        }
    }

    private void handleRescueRequest(MessageCivilian msg) {
        EntityID victimId = msg.getAgentID();
        
        // 日志去重
        int timeWindow = this.agentInfo.getTime() / 10;
        String msgKey = "C_" + victimId + "_" + timeWindow;
        if (!loggedMessageIds.contains(msgKey)) {
            loggedMessageIds.add(msgKey);
            System.err.println("[消防员分配器] 📨 收到平民消息: " + victimId + 
                               " 语音=" + msg.isRadio() +
                               " 埋压=" + (msg.isBuriednessDefined() ? msg.getBuriedness() : "?") +
                               " 位置=" + (msg.isPositionDefined() ? msg.getPosition() : "?"));
        }
        
        // 直接基于消息内容判断有效性，不依赖世界模型
        if (msg.isHPDefined() && msg.getHP() == 0) return;
        if (!msg.isBuriednessDefined() || msg.getBuriedness() == 0) return;
        if (!msg.isPositionDefined()) return; // 无位置信息则无法救援
        
        // 如果未处理过，直接加入任务
        if (!processedRescueMessages.contains(victimId)) {
            processedRescueMessages.add(victimId);
            rescueRequestTargets.add(victimId);
            rescuePriorityMap.put(victimId, PRIORITY_TYPE_CIVILIAN);
            System.err.println("[消防员分配器] ✅ 添加平民救援任务: " + victimId);
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
            System.err.println("[消防员分配器] 📨 收到警察救援请求: " + victimId);
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
            System.err.println("[消防员分配器] 📨 收到消防员救援请求: " + victimId);
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
            System.err.println("[消防员分配器] 📨 收到救护车救援请求: " + victimId);
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
                completedRescues.add(info.currentTask);
                System.err.println("[消防员分配器] ✅ 消防车 " + report.getSenderID() + " 完成任务: " + info.currentTask);
            }
        }
    }

    // ========== 任务管理 ==========
    private void updateTaskQueue() {
    int currentTime = this.agentInfo.getTime();
    
    for (EntityID target : rescueRequestTargets) {
        if (!completedRescues.contains(target)) {
            int currentCount = rescueAssignCount.getOrDefault(target, 0);
            if (currentCount < MAX_AGENTS_PER_RESCUE) {
                Integer priority = rescuePriorityMap.getOrDefault(target, PRIORITY_TYPE_CIVILIAN);
                addTask(target, priority);
            }
        }
    }
    
    for (EntityID target : fireTargets) {
        addTask(target, PRIORITY_FIRE);
    }
    
    taskQueue.removeIf(t -> currentTime - t.createTime > TASK_EXPIRE_TIME);
    
    // 统计任务队列中救援和灭火任务的数量
    int rescueCount = 0;
    int fireCount = 0;
    for (Task t : taskQueue) {
        if (t.priority <= PRIORITY_TYPE_CIVILIAN) {
            rescueCount++;
        } else if (t.priority == PRIORITY_FIRE) {
            fireCount++;
        }
    }
    System.err.println("[消防分配器] 时间=" + currentTime + 
                       " 队列: 救援=" + rescueCount + 
                       " 灭火=" + fireCount);
}

    private void addTask(EntityID target, int priority) {
        for (Task t : taskQueue) {
            if (t.target.equals(target) && t.priority <= priority) return;
        }
        taskQueue.offer(new Task(target, priority, this.agentInfo.getTime()));
    }

    private void assignTasks() {
        List<Task> tasks = new ArrayList<>(taskQueue);
        tasks.sort(Comparator.comparingInt(t -> t.priority));
        
        Set<EntityID> assignedThisRound = new HashSet<>();
        
        for (Task task : tasks) {
            if (assignedThisRound.contains(task.target)) continue;
            
            int currentCount = rescueAssignCount.getOrDefault(task.target, 0);
            if (task.priority != PRIORITY_FIRE && currentCount >= MAX_AGENTS_PER_RESCUE) {
                continue;
            }
            
            EntityID bestFireman = findBestIdleFireman(task.target);
            if (bestFireman != null) {
                assignTaskToFireman(bestFireman, task.target, task.priority);
                assignedThisRound.add(task.target);
                rescueAssignCount.put(task.target, currentCount + 1);
                
                String typeName = (task.priority == PRIORITY_FIRE) ? "灭火" : "救援";
                System.err.println("[消防员分配器] ✅ 分配" + typeName + "任务: 消防车=" + bestFireman + 
                                   " 目标=" + task.target);
            }
        }
        
        taskQueue.removeIf(t -> assignedThisRound.contains(t.target));
        rescueRequestTargets.removeAll(assignedThisRound);
        fireTargets.removeAll(assignedThisRound);
    }

    private EntityID findBestIdleFireman(EntityID target) {
        EntityID bestFireman = null;
        double bestDistance = Double.MAX_VALUE;
        
        EntityID targetPos = getTargetPosition(target);
        if (targetPos == null) return null;
        
        for (Map.Entry<EntityID, FireInfo> entry : firemanMap.entrySet()) {
            FireInfo info = entry.getValue();
            if (info.currentTask != null) continue;
            
            double distance = getDistance(entry.getKey(), targetPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestFireman = entry.getKey();
            }
        }
        return bestFireman;
    }

    private EntityID getTargetPosition(EntityID target) {
        StandardEntity e = worldInfo.getEntity(target);
        if (e instanceof Human) {
            Human h = (Human) e;
            return h.isPositionDefined() ? h.getPosition() : null;
        } else if (e instanceof Building) {
            return target;
        }
        return null;
    }

    private void assignTaskToFireman(EntityID firemanId, EntityID task, int priority) {
        assignedFiremanTasks.put(firemanId, task);
        tasksInProgress.add(task);
        FireInfo info = firemanMap.get(firemanId);
        if (info != null) {
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
        // 通过世界模型检查任务是否完成
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
                System.err.println("[消防员分配器] ✅ 救援完成: " + task);
            }
        }
        
        // 检查灭火任务完成
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
                    System.err.println("[消防员分配器] ✅ 灭火完成: " + task);
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

    // ========== 内部类 ==========
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