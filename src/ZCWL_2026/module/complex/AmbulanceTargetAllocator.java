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

public class AmbulanceTargetAllocator extends adf.core.component.module.complex.AmbulanceTargetAllocator {

    private static final int TASK_TYPE_LOAD = 1;
    private static final int PRIORITY_FIRE_REPORT = -1;
    private static final int PRIORITY_CIVILIAN = 0;
    private static final int PRIORITY_URGENT = -2;

    private static final int MAX_AMBULANCE_PER_VICTIM = 1;
    private static final int TASK_TIMEOUT = 50;
    private static final int URGENT_DAMAGE_THRESHOLD = 50;
    private static final int ACTIVE_SCAN_INTERVAL = 5;

    private PathPlanning pathPlanning;
    
    private Set<EntityID> civilianTasks;
    private Set<EntityID> fireReportedTasks;
    private Set<EntityID> urgentTasks;
    
    private Map<EntityID, AmbulanceTeamInfo> ambulanceInfoMap;
    
    private Map<EntityID, Integer> taskAssignCount;
    private Set<EntityID> completedTasks;
    private Map<EntityID, Integer> taskStartTime;
    
    private Set<EntityID> invalidBuildings;
    private Set<EntityID> victimsWaitingForLoad;
    
    private int lastScanTime;

    public AmbulanceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, 
                                     ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        
        this.civilianTasks = new HashSet<>();
        this.fireReportedTasks = new HashSet<>();
        this.urgentTasks = new HashSet<>();
        this.ambulanceInfoMap = new HashMap<>();
        this.taskAssignCount = new HashMap<>();
        this.completedTasks = new HashSet<>();
        this.taskStartTime = new HashMap<>();
        this.invalidBuildings = new HashSet<>();
        this.victimsWaitingForLoad = new HashSet<>();
        this.lastScanTime = 0;
        
        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                    "AmbulanceTargetAllocator.PathPlanning",
                    "ZCWL_2026.module.algorithm.PathPlanning");
                break;
        }
        
        System.err.println("[救护车分配器] 已加载 - 优化版");
    }

    // ==================== 建筑有效性检查 ====================
    
    private boolean isValidBuilding(EntityID buildingId) {
        if (buildingId == null) return false;
        if (invalidBuildings.contains(buildingId)) return false;
        
        StandardEntity entity = this.worldInfo.getEntity(buildingId);
        if (!(entity instanceof Building)) return true;
        
        Building building = (Building) entity;
        if (!building.isXDefined() || !building.isYDefined()) {
            invalidBuildings.add(buildingId);
            return false;
        }
        
        int x = building.getX();
        int y = building.getY();
        if (Math.abs(x) <= 10 && Math.abs(y) <= 10) {
            invalidBuildings.add(buildingId);
            return false;
        }
        
        return true;
    }
    
    private boolean isValidPositionEntity(EntityID positionId) {
        if (positionId == null) return false;
        StandardEntity entity = this.worldInfo.getEntity(positionId);
        if (entity == null) return false;
        if (entity instanceof Building) return isValidBuilding(positionId);
        if (entity instanceof Road) {
            Road road = (Road) entity;
            return road.isXDefined() && road.isYDefined();
        }
        return true;
    }
    
    private boolean isInRefuge(Human human) {
        if (!human.isPositionDefined()) return false;
        EntityID pos = human.getPosition();
        if (pos == null) return false;
        StandardEntity posEntity = this.worldInfo.getEntity(pos);
        return posEntity != null && posEntity.getStandardURN() == REFUGE;
    }
    
    private boolean isVictimAlreadyLoaded(EntityID victimId) {
        for (AmbulanceTeamInfo info : ambulanceInfoMap.values()) {
            if (info.transportHuman != null && info.transportHuman.equals(victimId)) {
                return true;
            }
        }
        return victimsWaitingForLoad.contains(victimId);
    }
    
    // ==================== 关键修复：检查是否为救护车 ====================
    
    /**
     * 检查ID是否为救护车（禁止救护车装载自己）
     */
    private boolean isAmbulanceTeam(EntityID id) {
        if (id == null) return false;
        StandardEntity entity = this.worldInfo.getEntity(id);
        return entity instanceof AmbulanceTeam;
    }
    
    /**
     * 验证目标是否为有效的平民
     */
    private boolean isValidTarget(EntityID target) {
        if (target == null) {
            System.err.println("[救护车分配器] ❌ 目标为null");
            return false;
        }
        
        StandardEntity entity = this.worldInfo.getEntity(target);
        if (entity == null) {
            System.err.println("[救护车分配器] ❌ 目标 " + target + " 不存在");
            return false;
        }
        
        // ========== 关键修复：禁止救护车装载自己 ==========
        if (entity instanceof AmbulanceTeam) {
            System.err.println("[救护车分配器] ❌ 错误！试图分配救护车自己作为目标！");
            System.err.println("  救护车ID: " + target);
            return false;
        }
        
        // 必须是平民
        if (!(entity instanceof Civilian)) {
            System.err.println("[救护车分配器] ❌ 目标 " + target + " 不是平民，类型: " + 
                               entity.getClass().getSimpleName());
            return false;
        }
        
        return true;
    }

    // ==================== 增强版平民扫描 ====================
    
    private void scanCivilians() {
        Set<EntityID> newCivilians = new HashSet<>();
        Set<EntityID> newUrgent = new HashSet<>();
        
        for (StandardEntity entity : this.worldInfo.getEntitiesOfType(CIVILIAN)) {
            Human human = (Human) entity;
            EntityID victimId = human.getID();
            
            // ========== 跳过救护车自己 ==========
            if (isAmbulanceTeam(victimId)) {
                continue;
            }
            
            if (completedTasks.contains(victimId)) continue;
            if (isVictimAlreadyLoaded(victimId)) {
                completedTasks.add(victimId);
                continue;
            }
            
            if (!human.isPositionDefined()) continue;
            
            EntityID pos = human.getPosition();
            if (pos == null || !isValidPositionEntity(pos)) continue;
            
            if (human.isHPDefined() && human.getHP() == 0) {
                completedTasks.add(victimId);
                continue;
            }
            
            boolean isBuried = human.isBuriednessDefined() && human.getBuriedness() > 0;
            boolean hasDamage = human.isDamageDefined() && human.getDamage() > 0;
            boolean isInRefuge = isInRefuge(human);
            
            if (isInRefuge) {
                completedTasks.add(victimId);
                continue;
            }
            
            if (!isBuried && hasDamage) {
                newCivilians.add(victimId);
                
                if (human.getDamage() > URGENT_DAMAGE_THRESHOLD) {
                    newUrgent.add(victimId);
                }
            }
        }
        
        this.urgentTasks.addAll(newUrgent);
        this.civilianTasks.addAll(newCivilians);
        this.civilianTasks.removeAll(this.fireReportedTasks);
        
        if (!newCivilians.isEmpty()) {
            System.err.println("[救护车分配器] 扫描到 " + newCivilians.size() + " 个平民等待装载");
        }
    }
    
    /**
     * 主动扫描平民（修复漏救问题）
     */
    private void activeScanForVictims() {
        int currentTime = this.agentInfo.getTime();
        if (currentTime - lastScanTime < ACTIVE_SCAN_INTERVAL) return;
        lastScanTime = currentTime;
        
        System.err.println("[救护车分配器] 🔍 主动扫描所有平民...");
        
        int foundCount = 0;
        
        for (StandardEntity entity : this.worldInfo.getEntitiesOfType(CIVILIAN)) {
            Human human = (Human) entity;
            EntityID victimId = human.getID();
            
            // ========== 跳过救护车自己 ==========
            if (isAmbulanceTeam(victimId)) {
                continue;
            }
            
            if (completedTasks.contains(victimId)) continue;
            if (isVictimAlreadyLoaded(victimId)) {
                completedTasks.add(victimId);
                continue;
            }
            
            if (!human.isPositionDefined()) continue;
            
            EntityID pos = human.getPosition();
            if (pos == null || !isValidPositionEntity(pos)) continue;
            
            if (human.isHPDefined() && human.getHP() == 0) {
                completedTasks.add(victimId);
                continue;
            }
            
            boolean isBuried = human.isBuriednessDefined() && human.getBuriedness() > 0;
            boolean hasDamage = human.isDamageDefined() && human.getDamage() > 0;
            boolean isInRefuge = isInRefuge(human);
            
            if (isInRefuge) {
                completedTasks.add(victimId);
                continue;
            }
            
            if (!isBuried && hasDamage) {
                if (!civilianTasks.contains(victimId) && !fireReportedTasks.contains(victimId)) {
                    System.err.println("╔══════════════════════════════════════════════════════════════╗");
                    System.err.println("║  [救护车分配器] 🚨 主动扫描发现需要救护的平民！              ║");
                    System.err.println("║  平民 ID: " + victimId);
                    System.err.println("║  伤害: " + human.getDamage());
                    System.err.println("║  位置: " + pos);
                    System.err.println("╚══════════════════════════════════════════════════════════════╝");
                    
                    civilianTasks.add(victimId);
                    foundCount++;
                    
                    if (human.getDamage() > URGENT_DAMAGE_THRESHOLD) {
                        urgentTasks.add(victimId);
                    }
                }
            }
        }
        
        if (foundCount > 0) {
            System.err.println("[救护车分配器] 主动扫描发现 " + foundCount + " 个待救护平民");
        }
    }
    
    private void cleanupCompletedVictims() {
        for (EntityID victimId : new ArrayList<>(fireReportedTasks)) {
            if (isVictimCompleted(victimId)) {
                completedTasks.add(victimId);
            }
        }
        
        for (EntityID victimId : new ArrayList<>(civilianTasks)) {
            if (isVictimCompleted(victimId)) {
                completedTasks.add(victimId);
            }
        }
        
        for (EntityID victimId : new ArrayList<>(urgentTasks)) {
            if (isVictimCompleted(victimId)) {
                urgentTasks.remove(victimId);
            }
        }
        
        fireReportedTasks.removeAll(completedTasks);
        civilianTasks.removeAll(completedTasks);
    }
    
    private boolean isVictimCompleted(EntityID victimId) {
        Human h = (Human) this.worldInfo.getEntity(victimId);
        if (h == null) return true;
        
        for (AmbulanceTeamInfo info : ambulanceInfoMap.values()) {
            if (info.transportHuman != null && info.transportHuman.equals(victimId)) {
                return true;
            }
        }
        
        if (h.isHPDefined() && h.getHP() == 0) return true;
        if (h.isDamageDefined() && h.getDamage() == 0) return true;
        if (!h.isPositionDefined()) return true;
        
        EntityID pos = h.getPosition();
        if (pos != null) {
            StandardEntity posEntity = this.worldInfo.getEntity(pos);
            if (posEntity != null && posEntity.getStandardURN() == REFUGE) return true;
        }
        
        if (!isValidPositionEntity(pos)) return true;
        
        return false;
    }
    
    private void cleanupExpiredTasks() {
        int currentTime = this.agentInfo.getTime();
        List<EntityID> expired = new ArrayList<>();
        
        for (Map.Entry<EntityID, Integer> entry : taskStartTime.entrySet()) {
            if (currentTime - entry.getValue() > TASK_TIMEOUT) {
                expired.add(entry.getKey());
            }
        }
        
        for (EntityID taskId : expired) {
            completedTasks.add(taskId);
            taskStartTime.remove(taskId);
            System.err.println("[救护车分配器] ⏰ 任务 " + taskId + " 已超时");
        }
    }
    
    private List<EntityID> getAllIdleAmbulances() {
        List<EntityID> result = new ArrayList<>();
        for (Map.Entry<EntityID, AmbulanceTeamInfo> entry : ambulanceInfoMap.entrySet()) {
            if (entry.getValue().currentTask == null && entry.getValue().transportHuman == null) {
                result.add(entry.getKey());
            }
        }
        return result;
    }
    
    private void assignTasks() {
        cleanupCompletedVictims();
        
        List<Task> allTasks = new ArrayList<>();
        
        // 紧急任务优先
        for (EntityID target : urgentTasks) {
            if (completedTasks.contains(target)) continue;
            Task task = validateTask(target);
            if (task != null) {
                task.priority = PRIORITY_URGENT;
                allTasks.add(task);
            }
        }
        
        // 消防员报告的任务
        for (EntityID target : fireReportedTasks) {
            if (completedTasks.contains(target)) continue;
            if (urgentTasks.contains(target)) continue;
            Task task = validateTask(target);
            if (task != null) {
                task.priority = PRIORITY_FIRE_REPORT;
                allTasks.add(task);
            }
        }
        
        // 普通平民任务
        for (EntityID target : civilianTasks) {
            if (completedTasks.contains(target)) continue;
            if (urgentTasks.contains(target)) continue;
            if (fireReportedTasks.contains(target)) continue;
            Task task = validateTask(target);
            if (task != null) {
                task.priority = PRIORITY_CIVILIAN;
                allTasks.add(task);
            }
        }
        
        allTasks.sort(Comparator.comparingInt(t -> t.priority));
        
        List<EntityID> idleAmbulances = getAllIdleAmbulances();
        
        if (idleAmbulances.isEmpty()) {
            return;
        }
        
        if (!allTasks.isEmpty()) {
            System.err.println("[救护车分配器] 空闲救护车: " + idleAmbulances.size() + 
                               " 辆，待分配任务: " + allTasks.size() +
                               " (紧急:" + urgentTasks.size() + ")");
        }
        
        Set<EntityID> assignedTasks = new HashSet<>();
        int assignedCount = 0;
        
        for (EntityID ambulanceId : idleAmbulances) {
            Task selectedTask = null;
            for (Task task : allTasks) {
                if (assignedTasks.contains(task.target)) continue;
                
                int currentCount = taskAssignCount.getOrDefault(task.target, 0);
                if (currentCount >= MAX_AMBULANCE_PER_VICTIM) continue;
                
                if (isVictimCompleted(task.target)) {
                    completedTasks.add(task.target);
                    continue;
                }
                
                selectedTask = task;
                break;
            }
            
            if (selectedTask != null) {
                assignTaskToAmbulance(ambulanceId, selectedTask.target, selectedTask.priority, selectedTask.taskType);
                int newCount = taskAssignCount.getOrDefault(selectedTask.target, 0) + 1;
                taskAssignCount.put(selectedTask.target, newCount);
                assignedTasks.add(selectedTask.target);
                assignedCount++;
                
                String priorityName = selectedTask.priority == PRIORITY_URGENT ? "紧急" :
                                      (selectedTask.priority == PRIORITY_FIRE_REPORT ? "消防员报告" : "平民");
                System.err.println("╔══════════════════════════════════════════════════════════════╗");
                System.err.println("║  [救护车分配器] 📍 分配任务 (" + priorityName + ")");
                System.err.println("║  救护车: " + ambulanceId);
                System.err.println("║  目标: " + selectedTask.target);
                System.err.println("║  位置: " + selectedTask.position);
                System.err.println("╚══════════════════════════════════════════════════════════════╝");
            }
        }
        
        if (assignedCount > 0) {
            System.err.println("[救护车分配器] ✅ 本轮分配了 " + assignedCount + " 辆救护车");
        }
        
        fireReportedTasks.removeAll(assignedTasks);
        civilianTasks.removeAll(assignedTasks);
        urgentTasks.removeAll(assignedTasks);
    }
    
    /**
     * 验证任务有效性（增强版，包含救护车自检）
     */
    private Task validateTask(EntityID target) {
        // ========== 关键修复：首先检查是否为有效目标（不是救护车自己） ==========
        if (!isValidTarget(target)) {
            completedTasks.add(target);
            return null;
        }
        
        Human h = (Human) this.worldInfo.getEntity(target);
        if (h == null) {
            completedTasks.add(target);
            return null;
        }
        
        if (!h.isPositionDefined()) {
            completedTasks.add(target);
            return null;
        }
        
        EntityID pos = h.getPosition();
        if (pos == null || !isValidPositionEntity(pos)) {
            completedTasks.add(target);
            return null;
        }
        
        StandardEntity posEntity = this.worldInfo.getEntity(pos);
        if (posEntity != null && posEntity.getStandardURN() == REFUGE) {
            completedTasks.add(target);
            return null;
        }
        
        if (h.isHPDefined() && h.getHP() == 0) {
            completedTasks.add(target);
            return null;
        }
        
        boolean isBuried = (h.isBuriednessDefined() && h.getBuriedness() > 0);
        boolean hasDamage = (h.isDamageDefined() && h.getDamage() > 0);
        
        if (isBuried) {
            completedTasks.add(target);
            return null;
        }
        if (!hasDamage) {
            completedTasks.add(target);
            return null;
        }
        
        return new Task(target, 0, pos, TASK_TYPE_LOAD);
    }
    
    private void assignTaskToAmbulance(EntityID ambulanceId, EntityID target, int priority, int taskType) {
        // ========== 最终安全检查：确保不会分配救护车自己 ==========
        if (ambulanceId.equals(target)) {
            System.err.println("[救护车分配器] ❌ 严重错误！试图分配救护车 " + ambulanceId + " 装载自己！");
            return;
        }
        
        AmbulanceTeamInfo info = ambulanceInfoMap.get(ambulanceId);
        if (info != null) {
            if (info.currentTask != null) {
                int oldCount = taskAssignCount.getOrDefault(info.currentTask, 0);
                if (oldCount > 0) {
                    taskAssignCount.put(info.currentTask, oldCount - 1);
                }
            }
            info.currentTask = target;
            info.currentTaskType = taskType;
            info.isBusy = true;
            info.commandTime = this.agentInfo.getTime();
            taskStartTime.putIfAbsent(target, this.agentInfo.getTime());
        }
    }
    
    private void checkCompletedTasks() {
        for (EntityID victimId : new ArrayList<>(taskAssignCount.keySet())) {
            if (isVictimCompleted(victimId)) {
                if (!completedTasks.contains(victimId)) {
                    completedTasks.add(victimId);
                    System.err.println("[救护车分配器] ✅ 平民 " + victimId + " 已完成");
                }
            }
        }
        
        for (EntityID completed : completedTasks) {
            civilianTasks.remove(completed);
            fireReportedTasks.remove(completed);
            urgentTasks.remove(completed);
            taskAssignCount.remove(completed);
            taskStartTime.remove(completed);
        }
        completedTasks.clear();
    }

    @Override
    public AmbulanceTargetAllocator calc() {
        cleanupExpiredTasks();
        scanCivilians();
        activeScanForVictims();
        checkCompletedTasks();
        assignTasks();
        return this;
    }

    @Override
    public Map<EntityID, EntityID> getResult() {
        Map<EntityID, EntityID> result = new HashMap<>();
        for (Map.Entry<EntityID, AmbulanceTeamInfo> e : ambulanceInfoMap.entrySet()) {
            AmbulanceTeamInfo info = e.getValue();
            if (info.currentTask != null && !completedTasks.contains(info.currentTask)) {
                // ========== 最终安全检查 ==========
                if (e.getKey().equals(info.currentTask)) {
                    System.err.println("[救护车分配器] ❌ 发现救护车 " + e.getKey() + " 的任务是自己，跳过");
                    continue;
                }
                result.put(e.getKey(), info.currentTask);
            }
        }
        return result;
    }

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
                
                // ========== 检查是否是救护车自己 ==========
                if (isAmbulanceTeam(victimId)) {
                    System.err.println("[救护车分配器] ⚠️ 收到救护车自己的消息，忽略");
                    continue;
                }
                
                System.err.println("╔══════════════════════════════════════════════════════════════╗");
                System.err.println("║  [救护车分配器] 📨 收到平民报告！                            ║");
                System.err.println("║  平民 ID: " + victimId);
                System.err.println("║  伤害: " + (mc.isDamageDefined() ? mc.getDamage() : "未定义"));
                System.err.println("║  埋压度: " + (mc.isBuriednessDefined() ? mc.getBuriedness() : "未定义"));
                System.err.println("║  位置: " + mc.getPosition());
                System.err.println("╚══════════════════════════════════════════════════════════════╝");
                
                StandardEntity victimEntity = this.worldInfo.getEntity(victimId);
                if (!(victimEntity instanceof Human)) continue;
                
                Human victim = (Human) victimEntity;
                
                if (!victim.isPositionDefined()) {
                    completedTasks.add(victimId);
                    continue;
                }
                
                EntityID pos = victim.getPosition();
                if (pos != null) {
                    StandardEntity posEntity = this.worldInfo.getEntity(pos);
                    if (posEntity != null && posEntity.getStandardURN() == REFUGE) {
                        completedTasks.add(victimId);
                        continue;
                    }
                }
                
                boolean isBuried = (mc.isBuriednessDefined() && mc.getBuriedness() > 0);
                boolean hasDamage = (mc.isDamageDefined() && mc.getDamage() > 0);
                
                if (!hasDamage) {
                    completedTasks.add(victimId);
                }
                
                if (!isBuried && hasDamage) {
                    if (!completedTasks.contains(victimId) && !fireReportedTasks.contains(victimId)) {
                        this.fireReportedTasks.add(victimId);
                        System.err.println("[救护车分配器] ✅ 平民 " + victimId + " 加入任务列表");
                    }
                }
            }
        }
        
        for (CommunicationMessage message : messageManager.getReceivedMessageList(MessageAmbulanceTeam.class)) {
            MessageAmbulanceTeam mat = (MessageAmbulanceTeam) message;
            MessageUtil.reflectMessage(this.worldInfo, mat);
            AmbulanceTeamInfo info = this.ambulanceInfoMap.get(mat.getAgentID());
            if (info == null) {
                info = new AmbulanceTeamInfo(mat.getAgentID());
                this.ambulanceInfoMap.put(mat.getAgentID(), info);
            }
            if (mat.getAction() == MessageAmbulanceTeam.ACTION_LOAD) {
                info.transportHuman = mat.getTargetID();
                info.isBusy = true;
                victimsWaitingForLoad.add(mat.getTargetID());
            } else if (mat.getAction() == MessageAmbulanceTeam.ACTION_UNLOAD) {
                info.transportHuman = null;
                info.isBusy = false;
                info.currentTask = null;
                victimsWaitingForLoad.clear();
            }
            if (currentTime >= info.commandTime + 2) {
                updateAmbulanceInfo(info, mat);
            }
        }
        
        for (CommunicationMessage message : messageManager.getReceivedMessageList(MessageReport.class)) {
            MessageReport report = (MessageReport) message;
            if (report.isDone()) {
                AmbulanceTeamInfo info = this.ambulanceInfoMap.get(report.getSenderID());
                if (info != null && info.currentTask != null) {
                    completedTasks.add(info.currentTask);
                    taskAssignCount.remove(info.currentTask);
                    taskStartTime.remove(info.currentTask);
                    victimsWaitingForLoad.remove(info.currentTask);
                    
                    System.err.println("[救护车分配器] ✅ 救护车 " + report.getSenderID() + 
                                       " 完成任务: " + info.currentTask);
                    
                    info.currentTask = null;
                    info.isBusy = false;
                }
            }
        }
        return this;
    }
    
    private void updateAmbulanceInfo(AmbulanceTeamInfo info, MessageAmbulanceTeam message) {
        if (message.isBuriednessDefined() && message.getBuriedness() > 0) {
            info.isBusy = false;
            return;
        }
        switch (message.getAction()) {
            case MessageAmbulanceTeam.ACTION_REST:
                info.isBusy = false;
                break;
            case MessageAmbulanceTeam.ACTION_MOVE:
                info.isBusy = true;
                break;
            case MessageAmbulanceTeam.ACTION_RESCUE:
            case MessageAmbulanceTeam.ACTION_LOAD:
                info.isBusy = true;
                break;
            case MessageAmbulanceTeam.ACTION_UNLOAD:
                info.isBusy = false;
                info.transportHuman = null;
                break;
        }
    }

    private static class AmbulanceTeamInfo {
        EntityID id;
        EntityID currentTask;
        int currentTaskType;
        EntityID transportHuman;
        boolean isBusy;
        int commandTime;
        
        AmbulanceTeamInfo(EntityID id) {
            this.id = id;
            this.currentTask = null;
            this.currentTaskType = -1;
            this.transportHuman = null;
            this.isBusy = false;
            this.commandTime = -1;
        }
    }
    
    private static class Task {
        EntityID target;
        int priority;
        EntityID position;
        int taskType;
        
        Task(EntityID target, int priority, EntityID position, int taskType) {
            this.target = target;
            this.priority = priority;
            this.position = position;
            this.taskType = taskType;
        }
    }
}