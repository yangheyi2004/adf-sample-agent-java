package ZCWL_2026.module.complex;

import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.centralized.CommandAmbulance;
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

public class AmbulanceTargetAllocator extends adf.core.component.module.complex.AmbulanceTargetAllocator {

    // 优先级常量（基于单位类型，数字越小优先级越高）
    private static final int PRIORITY_TYPE_FIRE = 0;      // 消防员
    private static final int PRIORITY_TYPE_POLICE = 1;    // 警察
    private static final int PRIORITY_TYPE_AMBULANCE = 2; // 救护车
    private static final int PRIORITY_TYPE_CIVILIAN = 3;  // 平民

    // 强制装载最高优先级（覆盖类型优先级）
    private static final int PRIORITY_FORCED_LOAD = 0;

    private static final int MAX_AMBULANCE_PER_VICTIM = 2;  // 每个单位最多辆救护车

    private PathPlanning pathPlanning;
    
    // 任务集合（只包含装载任务）
    private Set<EntityID> loadNowTasks;      // 已挖出且有伤害的单位
    private Set<EntityID> forcedLoadTasks;   // 强制装载任务（消防员指派）
    
    private Map<EntityID, AmbulanceTeamInfo> ambulanceTeamInfoMap;
    
    // 任务分配计数
    private Map<EntityID, Integer> victimAssignCount;
    private Set<EntityID> completedVictims;
    
    // 记录上一轮已发现的单位，避免重复日志
    private Set<EntityID> lastLoadNowVictims;

    public AmbulanceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, 
                                     ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.loadNowTasks = new HashSet<>();
        this.forcedLoadTasks = new HashSet<>();
        this.ambulanceTeamInfoMap = new HashMap<>();
        this.victimAssignCount = new HashMap<>();
        this.completedVictims = new HashSet<>();
        this.lastLoadNowVictims = new HashSet<>();
        
        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                    "AmbulanceTargetAllocator.PathPlanning",
                    "ZCWL_2026.module.algorithm.PathPlanning");
                break;
        }
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [救护车分配器] 已加载                                        ║");
        System.err.println("║  任务: 装载已挖出且有伤害的单位                                ║");
        System.err.println("║  优先级: 消防员(0) > 警察(1) > 救护车(2) > 平民(3)           ║");
        System.err.println("║  策略: 只分配给空闲救护车（不打断）                            ║");
        System.err.println("║  每个单位最多 " + MAX_AMBULANCE_PER_VICTIM + " 辆救护车         ║");
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
    }

    @Override
    public AmbulanceTargetAllocator resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        initAmbulanceInfo();
        return this;
    }

    @Override
    public AmbulanceTargetAllocator preparate() {
        super.preparate();
        if (this.getCountPrecompute() >= 2) return this;
        initAmbulanceInfo();
        return this;
    }

    private void initAmbulanceInfo() {
        for (EntityID id : this.worldInfo.getEntityIDsOfType(AMBULANCE_TEAM)) {
            this.ambulanceTeamInfoMap.put(id, new AmbulanceTeamInfo(id));
        }
        System.err.println("[救护车分配器] 初始化完成，救护车数量: " + ambulanceTeamInfoMap.size());
    }

    @Override
    public Map<EntityID, EntityID> getResult() {
        Map<EntityID, EntityID> result = new HashMap<>();
        for (Map.Entry<EntityID, AmbulanceTeamInfo> e : ambulanceTeamInfoMap.entrySet()) {
            AmbulanceTeamInfo info = e.getValue();
            if (info.currentTask != null && !completedVictims.contains(info.currentTask)) {
                result.put(e.getKey(), info.currentTask);
            }
        }
        return result;
    }

    @Override
    public AmbulanceTargetAllocator calc() {
        scanAllHumans();           // 扫描所有人类
        checkCompletedVictims();
        assignTasksByPriority();
        return this;
    }
    
    // ========== 扫描所有人类单位 ==========
    private void scanAllHumans() {
        Set<EntityID> currentLoadNowVictims = new HashSet<>();
        
        // 遍历所有类型的人类（平民、警察、消防员、救护车）
        for (StandardEntity entity : this.worldInfo.getEntitiesOfType(CIVILIAN, POLICE_FORCE, FIRE_BRIGADE, AMBULANCE_TEAM)) {
            if (!(entity instanceof Human)) continue;
            Human human = (Human) entity;
            EntityID victimId = human.getID();
            
            if (human.isHPDefined() && human.getHP() == 0) {
                completedVictims.add(victimId);
                continue;
            }
            
            boolean isBuried = (human.isBuriednessDefined() && human.getBuriedness() > 0);
            boolean hasDamage = (human.isDamageDefined() && human.getDamage() > 0);
            boolean isPositionDefined = human.isPositionDefined();
            
            // 已挖出且有伤害 -> 装载任务
            if (!isBuried && hasDamage && isPositionDefined) {
                currentLoadNowVictims.add(victimId);
                if (!lastLoadNowVictims.contains(victimId)) {
                    String typeName = getTypeName(human.getStandardURN());
                    System.err.println("╔══════════════════════════════════════════════════════════════╗");
                    System.err.println("║  [救护车分配器] 🚨 主动扫描发现已挖出单位！                  ║");
                    System.err.println("║  单位: " + victimId + " (" + typeName + ") 伤害=" + human.getDamage());
                    System.err.println("║  位置: " + human.getPosition());
                    System.err.println("╚══════════════════════════════════════════════════════════════╝");
                }
            }
        }
        
        loadNowTasks.clear();
        loadNowTasks.addAll(currentLoadNowVictims);
        forcedLoadTasks.removeAll(currentLoadNowVictims);
        
        lastLoadNowVictims.clear();
        lastLoadNowVictims.addAll(currentLoadNowVictims);
        
        if (!currentLoadNowVictims.isEmpty()) {
            System.err.println("[救护车分配器] 当前有 " + currentLoadNowVictims.size() + " 个单位等待装载");
        }
    }

    // ========== 核心分配逻辑（只分配空闲救护车，不打断） ==========
    
    /**
     * 找最近的空闲救护车
     */
    private EntityID findBestIdleAmbulance(EntityID targetPos) {
        EntityID bestAmbulance = null;
        double bestDistance = Double.MAX_VALUE;
        int idleCount = 0;
        
        for (Map.Entry<EntityID, AmbulanceTeamInfo> entry : ambulanceTeamInfoMap.entrySet()) {
            EntityID ambulanceId = entry.getKey();
            AmbulanceTeamInfo info = entry.getValue();
            if (info.currentTask != null) continue;  // 只考虑空闲救护车
            
            idleCount++;
            if (!isReachable(ambulanceId, targetPos)) continue;
            
            double distance = getDistance(ambulanceId, targetPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestAmbulance = ambulanceId;
            }
        }
        
        if (bestAmbulance != null) {
            System.err.println("[救护车分配器] ✅ 找到空闲救护车: " + bestAmbulance + 
                               " 距离=" + (int)bestDistance + " (空闲总数=" + idleCount + ")");
        } else {
            System.err.println("[救护车分配器] ❌ 无空闲救护车 (空闲总数=" + idleCount + ")");
        }
        return bestAmbulance;
    }
    
    private void assignTasksByPriority() {
        // 构建所有任务列表
        List<Task> allTasks = new ArrayList<>();
        
        // 强制装载任务（优先级0）
        for (EntityID target : forcedLoadTasks) {
            if (completedVictims.contains(target)) continue;
            Task task = validateTask(target, true);
            if (task != null) allTasks.add(task);
        }
        
        // 普通装载任务（根据类型计算优先级）
        for (EntityID target : loadNowTasks) {
            if (completedVictims.contains(target)) continue;
            Task task = validateTask(target, false);
            if (task != null) allTasks.add(task);
        }
        
        // 按优先级排序（数字小优先）
        allTasks.sort(Comparator.comparingInt(t -> t.priority));
        
        System.err.println("[救护车分配器] 开始分配任务，待分配任务数: " + allTasks.size());
        for (Task task : allTasks) {
            String priorityName = getPriorityName(task.priority);
            System.err.println("[救护车分配器]   任务: " + task.target + 
                               " 类型=" + getTypeName(task.type) +
                               " 优先级=" + task.priority + "(" + priorityName + ")");
        }
        
        Set<EntityID> assignedTasks = new HashSet<>();
        
        for (Task task : allTasks) {
            if (assignedTasks.contains(task.target)) continue;
            
            int currentCount = victimAssignCount.getOrDefault(task.target, 0);
            if (currentCount >= MAX_AMBULANCE_PER_VICTIM) {
                System.err.println("[救护车分配器] 任务 " + task.target + " 已满 (" + currentCount + "/" + MAX_AMBULANCE_PER_VICTIM + ")，跳过");
                continue;
            }
            
            EntityID bestAmbulance = findBestIdleAmbulance(task.position);
            if (bestAmbulance != null) {
                assignTaskToAmbulance(bestAmbulance, task.target, task.priority);
                assignedTasks.add(task.target);
                victimAssignCount.put(task.target, currentCount + 1);
                
                String priorityName = getPriorityName(task.priority);
                System.err.println("╔══════════════════════════════════════════════════════════════╗");
                System.err.println("║  [救护车分配器] 📍 分配任务！                                 ║");
                System.err.println("║  救护车: " + bestAmbulance);
                System.err.println("║  目标: " + task.target + " (" + getTypeName(task.type) + ")");
                System.err.println("║  优先级: " + task.priority + "(" + priorityName + ")");
                System.err.println("╚══════════════════════════════════════════════════════════════╝");
            }
        }
        
        // 清理已分配的任务
        loadNowTasks.removeAll(assignedTasks);
        forcedLoadTasks.removeAll(assignedTasks);
    }

    /**
     * 验证任务有效性，并返回带有优先级的 Task 对象
     * @param target 目标ID
     * @param isForcedLoad 是否是强制装载（消防员指派）
     */
    private Task validateTask(EntityID target, boolean isForcedLoad) {
        Human h = (Human) this.worldInfo.getEntity(target);
        if (h == null) return null;
        if (!h.isPositionDefined()) return null;
        if (h.isHPDefined() && h.getHP() == 0) {
            completedVictims.add(target);
            return null;
        }
        boolean isBuried = (h.isBuriednessDefined() && h.getBuriedness() > 0);
        boolean hasDamage = (h.isDamageDefined() && h.getDamage() > 0);
        
        // 装载任务要求：未被掩埋且有伤害
        if (isBuried || !hasDamage) {
            completedVictims.add(target);
            return null;
        }
        
        int priority;
        if (isForcedLoad) {
            priority = PRIORITY_FORCED_LOAD;  // 强制装载最高优先级
        } else {
            priority = getTypePriority(h.getStandardURN());
        }
        
        return new Task(target, priority, h.getPosition(), h.getStandardURN());
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
    
    private String getTypeName(StandardEntityURN type) {
        switch (type) {
            case FIRE_BRIGADE: return "消防员";
            case POLICE_FORCE: return "警察";
            case AMBULANCE_TEAM: return "救护车";
            case CIVILIAN: return "平民";
            default: return "未知";
        }
    }
    
    private String getPriorityName(int priority) {
        if (priority == PRIORITY_FORCED_LOAD) return "强制装载";
        if (priority == PRIORITY_TYPE_FIRE) return "消防员";
        if (priority == PRIORITY_TYPE_POLICE) return "警察";
        if (priority == PRIORITY_TYPE_AMBULANCE) return "救护车";
        if (priority == PRIORITY_TYPE_CIVILIAN) return "平民";
        return "未知";
    }
    
    // ========== 辅助方法 ==========
    private boolean isReachable(EntityID ambulanceId, EntityID targetPos) {
        if (this.pathPlanning == null) return true;
        StandardEntity agent = this.worldInfo.getEntity(ambulanceId);
        if (!(agent instanceof AmbulanceTeam)) return false;
        AmbulanceTeam ambulance = (AmbulanceTeam) agent;
        if (!ambulance.isPositionDefined()) return false;
        List<EntityID> path = this.pathPlanning.getResult(ambulance.getPosition(), targetPos);
        return path != null && path.size() > 0;
    }
    
    private double getDistance(EntityID from, EntityID to) {
        if (this.pathPlanning != null) return this.pathPlanning.getDistance(from, to);
        StandardEntity fromEntity = this.worldInfo.getEntity(from);
        StandardEntity toEntity = this.worldInfo.getEntity(to);
        if (fromEntity != null && toEntity != null) {
            return this.worldInfo.getDistance(fromEntity, toEntity);
        }
        return Double.MAX_VALUE;
    }
    
    private void assignTaskToAmbulance(EntityID ambulanceId, EntityID target, int priority) {
        AmbulanceTeamInfo info = ambulanceTeamInfoMap.get(ambulanceId);
        if (info != null) {
            if (info.currentTask != null && !info.currentTask.equals(target)) {
                int oldCount = victimAssignCount.getOrDefault(info.currentTask, 0);
                if (oldCount > 0) victimAssignCount.put(info.currentTask, oldCount - 1);
            }
            info.currentTask = target;
            info.currentTaskPriority = priority;
            info.isBusy = true;
            info.commandTime = this.agentInfo.getTime();
            ambulanceTeamInfoMap.put(ambulanceId, info);
        }
    }
    
    private void checkCompletedVictims() {
        for (EntityID victimId : new ArrayList<>(victimAssignCount.keySet())) {
            Human h = (Human) this.worldInfo.getEntity(victimId);
            if (h == null) {
                completedVictims.add(victimId);
                continue;
            }
            // 检查是否已被装载
            for (AmbulanceTeamInfo info : ambulanceTeamInfoMap.values()) {
                if (info.transportHuman != null && info.transportHuman.equals(victimId)) {
                    completedVictims.add(victimId);
                    String typeName = getTypeName(h.getStandardURN());
                    System.err.println("[救护车分配器] ✅ " + typeName + " " + victimId + " 已被装载");
                    break;
                }
            }
            // 检查是否死亡
            if (h.isHPDefined() && h.getHP() == 0) {
                completedVictims.add(victimId);
                String typeName = getTypeName(h.getStandardURN());
                System.err.println("[救护车分配器] ❌ " + typeName + " " + victimId + " 已死亡");
            }
        }
        for (EntityID completed : completedVictims) {
            loadNowTasks.remove(completed);
            forcedLoadTasks.remove(completed);
            victimAssignCount.remove(completed);
        }
    }

    // ========== 消息处理 ==========
    @Override
    public AmbulanceTargetAllocator updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;
        if (this.pathPlanning != null) this.pathPlanning.updateInfo(messageManager);
        
        int currentTime = this.agentInfo.getTime();
        
        for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
            Class<? extends CommunicationMessage> messageClass = message.getClass();
            
            // 处理平民消息
            if (messageClass == MessageCivilian.class) {
                MessageCivilian mc = (MessageCivilian) message;
                MessageUtil.reflectMessage(this.worldInfo, mc);
                EntityID victimId = mc.getAgentID();
                boolean isBuried = (mc.isBuriednessDefined() && mc.getBuriedness() > 0);
                boolean hasDamage = (mc.isDamageDefined() && mc.getDamage() > 0);
                if (!hasDamage) continue;
                if (!isBuried && hasDamage) {
                    this.loadNowTasks.add(victimId);
                    System.err.println("[救护车分配器] 📡 收到平民消息: " + victimId + " 已挖出，添加装载任务");
                }
            }
            // 处理警察消息
            else if (messageClass == MessagePoliceForce.class) {
                MessagePoliceForce mpf = (MessagePoliceForce) message;
                MessageUtil.reflectMessage(this.worldInfo, mpf);
                EntityID victimId = mpf.getAgentID();
                boolean isBuried = (mpf.isBuriednessDefined() && mpf.getBuriedness() > 0);
                boolean hasDamage = (mpf.isDamageDefined() && mpf.getDamage() > 0);
                if (!hasDamage) continue;
                if (!isBuried && hasDamage) {
                    this.loadNowTasks.add(victimId);
                    System.err.println("[救护车分配器] 📡 收到警察消息: " + victimId + " 已挖出，添加装载任务");
                }
            }
            // 处理消防员消息
            else if (messageClass == MessageFireBrigade.class) {
                MessageFireBrigade mfb = (MessageFireBrigade) message;
                MessageUtil.reflectMessage(this.worldInfo, mfb);
                EntityID victimId = mfb.getAgentID();
                boolean isBuried = (mfb.isBuriednessDefined() && mfb.getBuriedness() > 0);
                boolean hasDamage = (mfb.isDamageDefined() && mfb.getDamage() > 0);
                if (!hasDamage) continue;
                if (!isBuried && hasDamage) {
                    this.loadNowTasks.add(victimId);
                    System.err.println("[救护车分配器] 📡 收到消防员消息: " + victimId + " 已挖出，添加装载任务");
                }
            }
            // 处理救护车消息
            else if (messageClass == MessageAmbulanceTeam.class) {
                MessageAmbulanceTeam mat = (MessageAmbulanceTeam) message;
                MessageUtil.reflectMessage(this.worldInfo, mat);
                EntityID victimId = mat.getAgentID();
                boolean isBuried = (mat.isBuriednessDefined() && mat.getBuriedness() > 0);
                boolean hasDamage = (mat.isDamageDefined() && mat.getDamage() > 0);
                if (!hasDamage) continue;
                if (!isBuried && hasDamage) {
                    this.loadNowTasks.add(victimId);
                    System.err.println("[救护车分配器] 📡 收到救护车消息: " + victimId + " 已挖出，添加装载任务");
                }
            }
            // 处理强制装载命令
            else if (messageClass == CommandAmbulance.class) {
                CommandAmbulance cmd = (CommandAmbulance) message;
                if (cmd.getAction() == CommandAmbulance.ACTION_LOAD && cmd.isBroadcast()) {
                    EntityID target = cmd.getTargetID();
                    if (target != null) {
                        this.forcedLoadTasks.add(target);
                        System.err.println("[救护车分配器] 🚨 收到强制装载命令: " + target);
                    }
                }
            }
        }
        
        // 处理救护车自身状态消息
        for (CommunicationMessage message : messageManager.getReceivedMessageList(MessageAmbulanceTeam.class)) {
            MessageAmbulanceTeam mat = (MessageAmbulanceTeam) message;
            MessageUtil.reflectMessage(this.worldInfo, mat);
            AmbulanceTeamInfo info = this.ambulanceTeamInfoMap.get(mat.getAgentID());
            if (info == null) {
                info = new AmbulanceTeamInfo(mat.getAgentID());
                this.ambulanceTeamInfoMap.put(mat.getAgentID(), info);
            }
            if (mat.getAction() == MessageAmbulanceTeam.ACTION_LOAD) {
                info.transportHuman = mat.getTargetID();
                info.isBusy = true;
            } else if (mat.getAction() == MessageAmbulanceTeam.ACTION_UNLOAD) {
                info.transportHuman = null;
                info.isBusy = false;
                info.currentTask = null;
            }
            if (currentTime >= info.commandTime + 2) {
                updateAmbulanceInfo(info, mat);
            }
        }
        
        // 处理任务完成报告
        for (CommunicationMessage message : messageManager.getReceivedMessageList(MessageReport.class)) {
            MessageReport report = (MessageReport) message;
            if (report.isDone()) {
                AmbulanceTeamInfo info = this.ambulanceTeamInfoMap.get(report.getSenderID());
                if (info != null) {
                    if (info.currentTask != null) {
                        int count = victimAssignCount.getOrDefault(info.currentTask, 0);
                        if (count > 0) victimAssignCount.put(info.currentTask, count - 1);
                    }
                    info.currentTask = null;
                    info.isBusy = false;
                    info.canNewAction = true;
                    System.err.println("[救护车分配器] 救护车 " + report.getSenderID() + " 完成任务");
                }
            }
        }
        return this;
    }
    
    private void updateAmbulanceInfo(AmbulanceTeamInfo info, MessageAmbulanceTeam message) {
        if (message.isBuriednessDefined() && message.getBuriedness() > 0) {
            info.isBusy = false;
            info.canNewAction = false;
            return;
        }
        switch (message.getAction()) {
            case MessageAmbulanceTeam.ACTION_REST:
                info.canNewAction = true;
                info.isBusy = false;
                break;
            case MessageAmbulanceTeam.ACTION_MOVE:
                info.isBusy = true;
                break;
            case MessageAmbulanceTeam.ACTION_RESCUE:
            case MessageAmbulanceTeam.ACTION_LOAD:
                info.isBusy = true;
                info.canNewAction = false;
                break;
            case MessageAmbulanceTeam.ACTION_UNLOAD:
                info.canNewAction = true;
                info.isBusy = false;
                info.transportHuman = null;
                break;
        }
    }

    // ========== 内部类 ==========
    private class AmbulanceTeamInfo {
        EntityID id;
        EntityID currentTask;
        int currentTaskPriority;
        EntityID transportHuman;
        boolean isBusy;
        boolean canNewAction;
        int commandTime;
        AmbulanceTeamInfo(EntityID id) {
            this.id = id;
            this.currentTask = null;
            this.currentTaskPriority = PRIORITY_TYPE_CIVILIAN; // 默认最低
            this.transportHuman = null;
            this.isBusy = false;
            this.canNewAction = true;
            this.commandTime = -1;
        }
    }
    
    private static class Task {
        EntityID target;
        int priority;
        EntityID position;
        StandardEntityURN type;  // 用于日志
        Task(EntityID target, int priority, EntityID position, StandardEntityURN type) {
            this.target = target;
            this.priority = priority;
            this.position = position;
            this.type = type;
        }
    }
}