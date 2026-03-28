package ZCWL_2026.module.complex;

import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.centralized.CommandAmbulance;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.communication.standard.bundle.information.MessagePoliceForce;
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

    // 优先级常量
    private static final int PRIORITY_LOAD_NOW = 0;        // 掩埋度为0的平民（已挖出，最高优先级）
    private static final int PRIORITY_FORCED_LOAD = 1;     // 强制装载（消防员指派）
    private static final int PRIORITY_SEARCH = 2;          // 搜索任务（探索建筑、寻找伤员）

    private static final int MAX_AMBULANCE_PER_VICTIM = 2;  // 每个平民最多辆救护车（实际使用中可调整）

    private PathPlanning pathPlanning;
    
    // 任务集合
    private Set<EntityID> loadNowTasks;      // 掩埋度为0的平民（已挖出）
    private Set<EntityID> forcedLoadTasks;   // 强制装载任务
    private Set<EntityID> searchTasks;       // 搜索任务
    
    private Map<EntityID, AmbulanceTeamInfo> ambulanceTeamInfoMap;
    
    // 任务分配计数
    private Map<EntityID, Integer> victimAssignCount;
    private Set<EntityID> completedVictims;
    
    // 记录上一轮已发现的平民，避免重复日志
    private Set<EntityID> lastLoadNowVictims;

    public AmbulanceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, 
                                     ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.loadNowTasks = new HashSet<>();
        this.forcedLoadTasks = new HashSet<>();
        this.searchTasks = new HashSet<>();
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
        System.err.println("║  每轮主动扫描场上所有平民                                      ║");
        System.err.println("║  优先级: 已挖出(0) > 强制装载(1) > 搜索(2)                    ║");
        System.err.println("║  策略: 空闲 > 打断低优先级任务 > 距离优先                     ║");
        System.err.println("║  每个平民最多 " + MAX_AMBULANCE_PER_VICTIM + " 辆救护车         ║");
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
        scanAllCivilians();
        checkCompletedVictims();
        assignTasksByPriority();
        return this;
    }
    
    // ========== 扫描平民 ==========
    private void scanAllCivilians() {
        Set<EntityID> currentLoadNowVictims = new HashSet<>();
        
        for (StandardEntity entity : this.worldInfo.getEntitiesOfType(CIVILIAN)) {
            if (entity instanceof Civilian) {
                Civilian civilian = (Civilian) entity;
                EntityID victimId = civilian.getID();
                
                if (civilian.isHPDefined() && civilian.getHP() == 0) {
                    completedVictims.add(victimId);
                    continue;
                }
                
                boolean isBuried = (civilian.isBuriednessDefined() && civilian.getBuriedness() > 0);
                boolean hasDamage = (civilian.isDamageDefined() && civilian.getDamage() > 0);
                boolean isPositionDefined = civilian.isPositionDefined();
                
                if (!isBuried && hasDamage && isPositionDefined) {
                    currentLoadNowVictims.add(victimId);
                    if (!lastLoadNowVictims.contains(victimId)) {
                        System.err.println("╔══════════════════════════════════════════════════════════════╗");
                        System.err.println("║  [救护车分配器] 🚨 主动扫描发现已挖出平民！最高优先级！       ║");
                        System.err.println("║  平民: " + victimId + " 伤害=" + civilian.getDamage());
                        System.err.println("║  位置: " + civilian.getPosition());
                        System.err.println("╚══════════════════════════════════════════════════════════════╝");
                    }
                }
            }
        }
        
        loadNowTasks.clear();
        loadNowTasks.addAll(currentLoadNowVictims);
        forcedLoadTasks.removeAll(currentLoadNowVictims);
        searchTasks.removeAll(currentLoadNowVictims);
        
        lastLoadNowVictims.clear();
        lastLoadNowVictims.addAll(currentLoadNowVictims);
        
        if (!currentLoadNowVictims.isEmpty()) {
            System.err.println("[救护车分配器] 当前有 " + currentLoadNowVictims.size() + " 个已挖出平民等待装载");
        }
    }

    // ========== 核心分配逻辑 ==========
    
    /**
     * 通用方法：为指定优先级的任务寻找最佳救护车（可打断低优先级任务）
     */
    private EntityID findBestAmbulanceWithInterrupt(Task task, int newPriority, String taskType) {
        EntityID bestAmbulance = null;
        double bestDistance = Double.MAX_VALUE;
        AmbulanceTeamInfo bestInfo = null;
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [救护车分配器] 🔍 为" + taskType + "任务寻找救护车         ║");
        System.err.println("║  目标: " + task.target);
        System.err.println("║  位置: " + task.position);
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
        
        int idleCount = 0;
        int busyCount = 0;
        int reachableIdleCount = 0;
        int reachableBusyCount = 0;
        int skippedByPriorityCount = 0;
        
        // 第一轮：找空闲的救护车
        for (Map.Entry<EntityID, AmbulanceTeamInfo> entry : ambulanceTeamInfoMap.entrySet()) {
            EntityID ambulanceId = entry.getKey();
            AmbulanceTeamInfo info = entry.getValue();
            
            if (info.currentTask == null) {
                idleCount++;
                boolean reachable = isReachable(ambulanceId, task.position);
                System.err.println("[救护车分配器] 空闲救护车 " + ambulanceId + ", 可达=" + reachable);
                if (reachable) {
                    reachableIdleCount++;
                    double distance = getDistance(ambulanceId, task.position);
                    System.err.println("[救护车分配器]   距离=" + (int)distance);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestAmbulance = ambulanceId;
                        bestInfo = info;
                    }
                }
            } else {
                busyCount++;
                System.err.println("[救护车分配器] 忙碌救护车 " + ambulanceId + 
                                   ", 当前任务=" + info.currentTask + 
                                   ", 优先级=" + info.currentTaskPriority);
            }
        }
        
        System.err.println("[救护车分配器] 统计: 空闲=" + idleCount + ", 忙碌=" + busyCount + 
                           ", 可达空闲=" + reachableIdleCount);
        
        if (bestAmbulance != null) {
            System.err.println("[救护车分配器] ✅ 分配空闲救护车: " + bestAmbulance + 
                               " 距离=" + (int)bestDistance);
            return bestAmbulance;
        }
        
        // 第二轮：没有空闲，找可以打断的低优先级任务
        System.err.println("[救护车分配器] ⚠️ 无空闲救护车，尝试打断低优先级任务");
        System.err.println("[救护车分配器] 打断条件: 当前任务优先级 > " + newPriority);
        
        bestDistance = Double.MAX_VALUE;
        
        for (Map.Entry<EntityID, AmbulanceTeamInfo> entry : ambulanceTeamInfoMap.entrySet()) {
            EntityID ambulanceId = entry.getKey();
            AmbulanceTeamInfo info = entry.getValue();
            
            if (info.currentTask == null) continue;
            
            boolean canInterrupt = (info.currentTaskPriority > newPriority);
            System.err.println("[救护车分配器] 检查救护车 " + ambulanceId + 
                               ": 当前任务优先级=" + info.currentTaskPriority + 
                               ", 可打断=" + canInterrupt);
            
            if (!canInterrupt) {
                skippedByPriorityCount++;
                System.err.println("[救护车分配器]   ⏭️ 跳过: 优先级 " + info.currentTaskPriority + 
                                   " 不高于 " + newPriority);
                continue;
            }
            
            boolean reachable = isReachable(ambulanceId, task.position);
            System.err.println("[救护车分配器]   可达=" + reachable);
            if (reachable) {
                reachableBusyCount++;
                double distance = getDistance(ambulanceId, task.position);
                System.err.println("[救护车分配器]   距离=" + (int)distance);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestAmbulance = ambulanceId;
                    bestInfo = info;
                }
            }
        }
        
        System.err.println("[救护车分配器] 统计: 忙碌=" + busyCount + 
                           ", 因优先级跳过=" + skippedByPriorityCount +
                           ", 可达忙碌=" + reachableBusyCount);
        
        if (bestAmbulance != null) {
            System.err.println("╔══════════════════════════════════════════════════════════════╗");
            System.err.println("║  [救护车分配器] 🔥 打断救护车 " + bestAmbulance + " 的任务！       ║");
            System.err.println("║  原任务: " + bestInfo.currentTask + " (优先级=" + bestInfo.currentTaskPriority + ")");
            System.err.println("║  新任务: " + task.target + " (优先级=" + newPriority + ")");
            System.err.println("║  距离: " + (int)bestDistance);
            System.err.println("╚══════════════════════════════════════════════════════════════╝");
            return bestAmbulance;
        }
        
        System.err.println("[救护车分配器] ❌ 无法找到合适的救护车执行" + taskType + "任务: " + task.target);
        return null;
    }

    private EntityID findBestIdleAmbulance(Task task) {
        EntityID bestAmbulance = null;
        double bestDistance = Double.MAX_VALUE;
        
        for (Map.Entry<EntityID, AmbulanceTeamInfo> entry : ambulanceTeamInfoMap.entrySet()) {
            EntityID ambulanceId = entry.getKey();
            AmbulanceTeamInfo info = entry.getValue();
            if (info.currentTask != null) continue;
            if (!isReachable(ambulanceId, task.position)) continue;
            double distance = getDistance(ambulanceId, task.position);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestAmbulance = ambulanceId;
            }
        }
        return bestAmbulance;
    }
    
    private void assignTasksByPriority() {
        List<Task> allTasks = new ArrayList<>();
        
        for (EntityID target : loadNowTasks) {
            if (completedVictims.contains(target)) continue;
            Task task = validateTask(target, PRIORITY_LOAD_NOW);
            if (task != null) allTasks.add(task);
        }
        for (EntityID target : forcedLoadTasks) {
            if (completedVictims.contains(target)) continue;
            Task task = validateTask(target, PRIORITY_FORCED_LOAD);
            if (task != null) allTasks.add(task);
        }
        for (EntityID target : searchTasks) {
            if (completedVictims.contains(target)) continue;
            Task task = validateTask(target, PRIORITY_SEARCH);
            if (task != null) allTasks.add(task);
        }
        
        allTasks.sort(Comparator.comparingInt(t -> t.priority));
        
        Set<EntityID> assignedTasks = new HashSet<>();
        
        for (Task task : allTasks) {
            if (assignedTasks.contains(task.target)) continue;
            int currentCount = victimAssignCount.getOrDefault(task.target, 0);
            if (currentCount >= MAX_AMBULANCE_PER_VICTIM) {
                System.err.println("[救护车分配器] 任务 " + task.target + " 已满 (" + currentCount + "/" + MAX_AMBULANCE_PER_VICTIM + ")，跳过");
                continue;
            }
            
            EntityID bestAmbulance = null;
            if (task.priority == PRIORITY_LOAD_NOW) {
                bestAmbulance = findBestAmbulanceWithInterrupt(task, PRIORITY_LOAD_NOW, "已挖出平民");
            } else if (task.priority == PRIORITY_FORCED_LOAD) {
                bestAmbulance = findBestAmbulanceWithInterrupt(task, PRIORITY_FORCED_LOAD, "强制装载");
            } else {
                bestAmbulance = findBestIdleAmbulance(task);
            }
            
            if (bestAmbulance != null) {
                assignTaskToAmbulance(bestAmbulance, task.target, task.priority);
                assignedTasks.add(task.target);
                victimAssignCount.put(task.target, currentCount + 1);
                
                String priorityName = task.priority == PRIORITY_LOAD_NOW ? "已挖出平民" :
                                      (task.priority == PRIORITY_FORCED_LOAD ? "强制装载" : "搜索");
                System.err.println("[救护车分配器] 📍 分配任务: 救护车=" + bestAmbulance + 
                                   ", 目标=" + task.target + 
                                   ", 优先级=" + task.priority + "(" + priorityName + ")");
            }
        }
        
        loadNowTasks.removeAll(assignedTasks);
        forcedLoadTasks.removeAll(assignedTasks);
        searchTasks.removeAll(assignedTasks);
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
    
    private Task validateTask(EntityID target, int priority) {
        Human h = (Human) this.worldInfo.getEntity(target);
        if (h == null) return null;
        if (!h.isPositionDefined()) return null;
        if (h.isHPDefined() && h.getHP() == 0) {
            completedVictims.add(target);
            return null;
        }
        boolean isBuried = (h.isBuriednessDefined() && h.getBuriedness() > 0);
        
        if (priority == PRIORITY_LOAD_NOW) {
            if (isBuried) return null;
            if (!h.isDamageDefined() || h.getDamage() == 0) {
                completedVictims.add(target);
                return null;
            }
        }
        if (priority == PRIORITY_FORCED_LOAD) {
            if (isBuried) return null;
            if (!h.isDamageDefined() || h.getDamage() == 0) {
                completedVictims.add(target);
                return null;
            }
        }
        if (priority == PRIORITY_SEARCH) {
            if (!h.isDamageDefined() || h.getDamage() == 0) {
                completedVictims.add(target);
                return null;
            }
            if (isBuried) return null; // 救护车不处理被掩埋者
        }
        return new Task(target, priority, h.getPosition());
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
            for (AmbulanceTeamInfo info : ambulanceTeamInfoMap.values()) {
                if (info.transportHuman != null && info.transportHuman.equals(victimId)) {
                    completedVictims.add(victimId);
                    System.err.println("[救护车分配器] ✅ 平民 " + victimId + " 已被装载");
                    break;
                }
            }
            if (h.isHPDefined() && h.getHP() == 0) {
                completedVictims.add(victimId);
                System.err.println("[救护车分配器] ❌ 平民 " + victimId + " 已死亡");
            }
        }
        for (EntityID completed : completedVictims) {
            loadNowTasks.remove(completed);
            forcedLoadTasks.remove(completed);
            searchTasks.remove(completed);
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
            
            if (messageClass == MessageCivilian.class) {
                MessageCivilian mc = (MessageCivilian) message;
                MessageUtil.reflectMessage(this.worldInfo, mc);
                EntityID victimId = mc.getAgentID();
                boolean isBuried = (mc.isBuriednessDefined() && mc.getBuriedness() > 0);
                boolean hasDamage = (mc.isDamageDefined() && mc.getDamage() > 0);
                if (!hasDamage) continue;
                if (!isBuried && hasDamage) {
                    System.err.println("[救护车分配器] 📡 收到平民消息: " + victimId + " 已挖出");
                } else if (isBuried && hasDamage) {
                    System.err.println("[救护车分配器] 📋 收到被困人员信息（已转消防员）: " + victimId + " (被掩埋)");
                }
            }
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
            else if (messageClass == MessageFireBrigade.class) {
                MessageFireBrigade mfb = (MessageFireBrigade) message;
                MessageUtil.reflectMessage(this.worldInfo, mfb);
                if (mfb.isBuriednessDefined() && mfb.getBuriedness() > 0) {
                    this.searchTasks.add(mfb.getAgentID());
                }
            }
            else if (messageClass == MessagePoliceForce.class) {
                MessagePoliceForce mpf = (MessagePoliceForce) message;
                MessageUtil.reflectMessage(this.worldInfo, mpf);
                if (mpf.isBuriednessDefined() && mpf.getBuriedness() > 0) {
                    this.searchTasks.add(mpf.getAgentID());
                }
            }
        }
        
        for (CommunicationMessage message : messageManager.getReceivedMessageList(MessageAmbulanceTeam.class)) {
            MessageAmbulanceTeam mat = (MessageAmbulanceTeam) message;
            MessageUtil.reflectMessage(this.worldInfo, mat);
            if (mat.isBuriednessDefined() && mat.getBuriedness() > 0) {
                this.searchTasks.add(mat.getAgentID());
            }
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
            this.currentTaskPriority = PRIORITY_SEARCH;
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
        Task(EntityID target, int priority, EntityID position) {
            this.target = target;
            this.priority = priority;
            this.position = position;
        }
    }
}