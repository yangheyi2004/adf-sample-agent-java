package ZCWL_2026.centralized;

import adf.core.component.communication.CommunicationMessage;
import adf.core.component.centralized.CommandExecutor;
import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.action.police.ActionClear;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.algorithm.Clustering;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;

public class PoliceCommandExecutor extends CommandExecutor<CommandPolice> {

    // ==================== 命令类型常量 ====================
    private static final int ACTION_UNKNOWN = -1;
    private static final int ACTION_REST = CommandPolice.ACTION_REST;
    private static final int ACTION_MOVE = CommandPolice.ACTION_MOVE;
    private static final int ACTION_CLEAR = CommandPolice.ACTION_CLEAR;
    private static final int ACTION_AUTONOMY = CommandPolice.ACTION_AUTONOMY;

    // ==================== 配置参数 ====================
    private static final int MAX_RETRY_COUNT = 3;
    private static final int MAX_STUCK_COUNT = 30;
    private static final int MAX_NO_PROGRESS = 20;
    private static final int REST_CHECK_INTERVAL = 20;  // 休息检查间隔（每20轮检查一次是否需要休息）
    
    // ==================== 成员变量 ====================
    private int commandType;
    private EntityID target;
    private EntityID commanderID;
    private boolean reportSent;
    private int retryCount;
    private int lastRestCheckTime;  // 上次检查休息的时间

    // 模块依赖
    private PathPlanning pathPlanning;
    private ExtAction actionExtClear;
    private ExtAction actionExtMove;
    private Clustering policeClustering;
    
    // 区域数据
    private List<EntityID> myZoneRoads;
    private Set<EntityID> allRoads;
    private Map<EntityID, Integer> roadBlockadeCount;
    
    // 区域占领状态
    private Set<EntityID> zoneClearedRoads;
    private Set<EntityID> globalClearedRoads;
    private EntityID zoneEntryPoint;
    private boolean hasEnteredZone;
    private int lastZoneCheckTime;
    
    // 救援通道
    private Set<EntityID> rescueRoutes;
    private Map<EntityID, Integer> rescueRoutePriority;
    
    // 任务记录
    private Set<EntityID> completedTasks;
    private Set<EntityID> ignoredTasks;
    
    // ========== 防卡死状态 ==========
    private EntityID lastPosition;
    private int stuckCounter;
    private EntityID lastTarget;
    private int noProgressCounter;

    // ==================== 构造函数 ====================
    public PoliceCommandExecutor(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                  ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        
        this.commandType = ACTION_UNKNOWN;
        this.target = null;
        this.commanderID = null;
        this.reportSent = false;
        this.retryCount = 0;
        this.hasEnteredZone = false;
        this.lastZoneCheckTime = -1;
        this.lastRestCheckTime = 0;
        
        this.lastPosition = null;
        this.stuckCounter = 0;
        this.lastTarget = null;
        this.noProgressCounter = 0;
        
        this.rescueRoutes = new HashSet<>();
        this.rescueRoutePriority = new HashMap<>();
        this.completedTasks = new HashSet<>();
        this.ignoredTasks = new HashSet<>();
        this.roadBlockadeCount = new HashMap<>();
        this.zoneClearedRoads = new HashSet<>();
        this.globalClearedRoads = new HashSet<>();
        
        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                    "PoliceCommandExecutor.PathPlanning",
                    "ZCWL_2026.module.algorithm.PathPlanning");
                this.actionExtClear = moduleManager.getExtAction(
                    "PoliceCommandExecutor.ActionExtClear",
                    "ZCWL_2026.extraction.ActionExtClear");
                this.actionExtMove = moduleManager.getExtAction(
                    "PoliceCommandExecutor.ActionExtMove",
                    "ZCWL_2026.extraction.ActionExtMove");
                this.policeClustering = moduleManager.getModule(
                    "PoliceCommandExecutor.Clustering",
                    "ZCWL_2026.module.algorithm.PoliceBalancedClustering");
                break;
        }
        
        initZones();
        
        logInfo("警车执行器已加载，负责区域道路数: " + 
                (myZoneRoads == null ? 0 : myZoneRoads.size()));
        if (zoneEntryPoint != null) {
            logInfo("区域入口点: " + zoneEntryPoint);
        }
    }

    // ==================== 初始化方法 ====================
    
    private void initZones() {
        this.allRoads = new HashSet<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(ROAD)) {
            this.allRoads.add(e.getID());
            if (e instanceof Road) {
                Road road = (Road) e;
                if (road.isBlockadesDefined()) {
                    roadBlockadeCount.put(road.getID(), road.getBlockades().size());
                } else {
                    roadBlockadeCount.put(road.getID(), 0);
                }
            }
        }
        
        if (this.policeClustering != null) {
            this.policeClustering.calc();
            int clusterIndex = this.policeClustering.getClusterIndex(this.agentInfo.getID());
            if (clusterIndex >= 0) {
                Collection<EntityID> zoneRoads = this.policeClustering.getClusterEntityIDs(clusterIndex);
                if (zoneRoads != null && !zoneRoads.isEmpty()) {
                    this.myZoneRoads = new ArrayList<>(zoneRoads);
                    sortZoneRoadsByDistance();
                    this.zoneEntryPoint = findZoneEntryPoint();
                    logInfo("使用均衡聚类，负责区域道路数: " + this.myZoneRoads.size());
                    return;
                }
            }
        }
        
        fallbackZoneDivision();
        sortZoneRoadsByDistance();
        this.zoneEntryPoint = findZoneEntryPoint();
    }
    
    private void sortZoneRoadsByDistance() {
        if (myZoneRoads == null || myZoneRoads.isEmpty()) return;
        
        EntityID currentPos = this.agentInfo.getPosition();
        myZoneRoads.sort((a, b) -> {
            int distA = getDistance(currentPos, a);
            int distB = getDistance(currentPos, b);
            return Integer.compare(distA, distB);
        });
    }
    
    private EntityID findZoneEntryPoint() {
        if (myZoneRoads == null || myZoneRoads.isEmpty()) return null;
        
        EntityID currentPos = this.agentInfo.getPosition();
        EntityID nearest = null;
        int minDistance = Integer.MAX_VALUE;
        
        for (EntityID roadId : myZoneRoads) {
            int dist = getDistance(currentPos, roadId);
            if (dist < minDistance) {
                minDistance = dist;
                nearest = roadId;
            }
        }
        return nearest;
    }
    
    private void fallbackZoneDivision() {
        List<EntityID> allPolice = new ArrayList<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(POLICE_FORCE)) {
            allPolice.add(e.getID());
        }
        allPolice.sort(Comparator.comparingInt(EntityID::getValue));
        
        int myIndex = allPolice.indexOf(this.agentInfo.getID());
        if (myIndex < 0) {
            this.myZoneRoads = new ArrayList<>(this.allRoads);
            return;
        }
        
        int totalPolice = allPolice.size();
        if (totalPolice == 0) {
            this.myZoneRoads = new ArrayList<>(this.allRoads);
            return;
        }
        
        List<EntityID> sortedRoads = new ArrayList<>(this.allRoads);
        sortedRoads.sort(Comparator.comparingInt(EntityID::getValue));
        
        int roadsPerPolice = sortedRoads.size() / totalPolice;
        int start = myIndex * roadsPerPolice;
        int end = (myIndex == totalPolice - 1) ? sortedRoads.size() : (myIndex + 1) * roadsPerPolice;
        
        this.myZoneRoads = new ArrayList<>();
        for (int i = start; i < end && i < sortedRoads.size(); i++) {
            this.myZoneRoads.add(sortedRoads.get(i));
        }
        
        logInfo("回退均分，负责区域道路数: " + this.myZoneRoads.size());
    }
    
    private void updateZoneEntryPoint() {
        if (myZoneRoads == null || myZoneRoads.isEmpty()) return;
        
        EntityID currentPos = this.agentInfo.getPosition();
        
        if (hasEnteredZone && myZoneRoads.contains(currentPos)) {
            return;
        }
        
        EntityID nearest = null;
        int minDistance = Integer.MAX_VALUE;
        
        for (EntityID roadId : myZoneRoads) {
            int dist = getDistance(currentPos, roadId);
            if (dist < minDistance) {
                minDistance = dist;
                nearest = roadId;
            }
        }
        
        if (nearest != null && !nearest.equals(zoneEntryPoint)) {
            zoneEntryPoint = nearest;
        }
    }
    
    private void updateRoadBlockades() {
        for (EntityID changedId : this.worldInfo.getChanged().getChangedEntities()) {
            StandardEntity entity = this.worldInfo.getEntity(changedId);
            if (entity instanceof Road) {
                Road road = (Road) entity;
                int blockadeCount = road.isBlockadesDefined() ? road.getBlockades().size() : 0;
                roadBlockadeCount.put(road.getID(), blockadeCount);
                
                if (blockadeCount == 0) {
                    globalClearedRoads.add(road.getID());
                    
                    boolean isInZone = myZoneRoads != null && myZoneRoads.contains(road.getID());
                    if (isInZone) {
                        if (!zoneClearedRoads.contains(road.getID())) {
                            zoneClearedRoads.add(road.getID());
                            int totalZoneRoads = myZoneRoads.size();
                            int clearedCount = zoneClearedRoads.size();
                            logInfo("区域内道路 " + road.getID() + " 已清理，进度: " + 
                                    clearedCount + "/" + totalZoneRoads);
                        }
                    }
                    
                    ignoredTasks.add(road.getID());
                    rescueRoutes.remove(road.getID());
                    rescueRoutePriority.remove(road.getID());
                    
                    if (target != null && target.equals(road.getID())) {
                        logInfo("道路 " + road.getID() + " 已清理，清除当前目标");
                        target = null;
                        retryCount = 0;
                    }
                }
            }
        }
        
        for (EntityID ignored : ignoredTasks) {
            completedTasks.add(ignored);
        }
        ignoredTasks.clear();
    }
    
    // ==================== 防卡死检查方法 ====================
    
    private boolean isStuck() {
        EntityID currentPos = this.agentInfo.getPosition();
        
        if (lastPosition != null && lastPosition.equals(currentPos)) {
            stuckCounter++;
            if (stuckCounter > MAX_STUCK_COUNT) {
                System.err.println("[警车执行器] ID:" + this.agentInfo.getID() + 
                                   " ⚠️ 卡住超过 " + MAX_STUCK_COUNT + " 步，重置状态");
                stuckCounter = 0;
                return true;
            }
        } else {
            stuckCounter = 0;
            lastPosition = currentPos;
        }
        return false;
    }
    
    private boolean hasNoProgress() {
        if (lastTarget != null && lastTarget.equals(this.target)) {
            noProgressCounter++;
            if (noProgressCounter > MAX_NO_PROGRESS) {
                System.err.println("[警车执行器] ID:" + this.agentInfo.getID() + 
                                   " ⚠️ 对目标 " + this.target + " 无进展超过20步，放弃");
                noProgressCounter = 0;
                return true;
            }
        } else {
            noProgressCounter = 0;
            lastTarget = this.target;
        }
        return false;
    }
    
    // ==================== 新增：全局障碍物检查 ====================
    
    /**
     * 检查全图是否还有障碍物
     */
    private boolean hasAnyBlockadeGlobal() {
        for (EntityID roadId : allRoads) {
            if (hasBlockades(roadId) && !globalClearedRoads.contains(roadId)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检查是否需要休息（基于伤害值）
     */
    private boolean shouldRest() {
        PoliceForce police = (PoliceForce) this.agentInfo.me();
        int damage = police.getDamage();
        
        // 如果伤害超过50，需要休息
        if (damage > 50) {
            logInfo("伤害过高 (" + damage + ")，需要休息");
            return true;
        }
        
        // 每20轮检查一次，避免频繁去避难所
        int currentTime = this.agentInfo.getTime();
        if (currentTime - lastRestCheckTime < REST_CHECK_INTERVAL) {
            return false;
        }
        
        // 如果伤害超过30，每10轮去一次避难所
        if (damage > 30) {
            lastRestCheckTime = currentTime;
            return true;
        }
        
        return false;
    }
    
    /**
     * 计算全图障碍物总数（用于日志）
     */
    private int getTotalBlockadeCount() {
        int count = 0;
        for (EntityID roadId : allRoads) {
            if (hasBlockades(roadId)) {
                count++;
            }
        }
        return count;
    }
    
    // ==================== 命令处理 ====================
    
    @Override
    public CommandExecutor<CommandPolice> setCommand(CommandPolice command) {
        EntityID agentID = this.agentInfo.getID();
        
        if (command.isToIDDefined() && 
            Objects.requireNonNull(command.getToID()).getValue() == agentID.getValue()) {
            
            this.commandType = command.getAction();
            this.target = command.getTargetID();
            this.commanderID = command.getSenderID();
            this.reportSent = false;
            
            this.lastTarget = null;
            this.noProgressCounter = 0;
            
            logCommand(command.getAction(), this.target);
        }
        return this;
    }

    @Override
    public CommandExecutor<CommandPolice> updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;
        
        if (this.pathPlanning != null) {
            this.pathPlanning.updateInfo(messageManager);
        }
        if (this.actionExtClear != null) {
            this.actionExtClear.updateInfo(messageManager);
        }
        if (this.actionExtMove != null) {
            this.actionExtMove.updateInfo(messageManager);
        }
        
        updateRoadBlockades();
        
        int currentTime = this.agentInfo.getTime();
        if (currentTime - lastZoneCheckTime > 10) {
            lastZoneCheckTime = currentTime;
            updateZoneEntryPoint();
        }
        
        if (!reportSent && isCommandCompleted()) {
            sendCompletionReport(messageManager);
        }
        return this;
    }
    
    private void sendCompletionReport(MessageManager messageManager) {
        if (this.commandType != ACTION_UNKNOWN && this.target != null) {
            messageManager.addMessage(new MessageReport(true, true, false, this.commanderID));
            reportSent = true;
            
            logSuccess("完成任务: " + this.target);
            
            this.commandType = ACTION_UNKNOWN;
            this.target = null;
            this.commanderID = null;
            this.retryCount = 0;
        }
    }

    @Override
    public CommandExecutor<CommandPolice> calc() {
        this.result = null;
        
        switch (this.commandType) {
            case ACTION_REST:
                this.result = handleRest();
                break;
            case ACTION_MOVE:
                this.result = handleMove();
                break;
            case ACTION_CLEAR:
                this.result = handleClear();
                break;
            case ACTION_AUTONOMY:
                this.result = handleAutonomy();
                break;
            default:
                this.result = handleAutonomy();
                break;
        }
        return this;
    }
    
    // ==================== 命令处理具体实现 ====================
    
    private Action handleRest() {
        EntityID position = this.agentInfo.getPosition();
        
        if (this.target != null) {
            if (position.getValue() != this.target.getValue()) {
                List<EntityID> path = getPath(position, this.target);
                if (path != null && !path.isEmpty()) {
                    return new ActionMove(path);
                }
            }
            return new ActionRest();
        }
        
        Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
        if (refuges.contains(position)) {
            return new ActionRest();
        }
        
        List<EntityID> path = getPath(position, refuges);
        if (path != null && !path.isEmpty()) {
            return new ActionMove(path);
        }
        return new ActionRest();
    }
    
    private Action handleMove() {
        if (this.target == null) return null;
        
        EntityID position = this.agentInfo.getPosition();
        
        if (position.getValue() == this.target.getValue()) {
            return null;
        }
        
        return this.actionExtMove.setTarget(this.target).calc().getAction();
    }
    
    private Action handleClear() {
        if (this.target == null) return null;
        
        if (!hasBlockades(this.target)) {
            logInfo("目标 " + this.target + " 已无路障，清除任务");
            this.target = null;
            return null;
        }
        
        Action action = this.actionExtClear.setTarget(this.target).calc().getAction();
        
        if (action == null && retryCount < MAX_RETRY_COUNT) {
            retryCount++;
            return handleMoveToTarget(this.target);
        }
        
        return action;
    }
    
    /**
     * 自主模式 - 强制优先清理障碍物
     */
    private Action handleAutonomy() {
        EntityID position = this.agentInfo.getPosition();
        int totalBlockades = getTotalBlockadeCount();
        
        // ========== 关键修复：优先检查全图是否有障碍物 ==========
        if (!hasAnyBlockadeGlobal()) {
            logInfo("全图已无障碍物！前往避难所休息");
            return handleRest();
        }
        
        // 防卡死检查
        if (isStuck()) {
            this.target = null;
            return handleRest();
        }
        
        if (hasNoProgress()) {
            this.target = null;
        }
        
        // 检查当前任务是否有效
        if (isCurrentTaskValid()) {
            logInfo("继续执行当前任务: " + this.target);
            return this.actionExtClear.setTarget(this.target).calc().getAction();
        }
        
        // ========== 新增：伤害过高时去避难所 ==========
        if (shouldRest()) {
            logInfo("伤害过高，前往避难所恢复");
            return handleRest();
        }
        
        // 检查是否在自己的区域内
        boolean inZone = (myZoneRoads != null && myZoneRoads.contains(position));
        
        if (!inZone) {
            hasEnteredZone = false;
            Action enterAction = tryEnterZone(position);
            if (enterAction != null) {
                return enterAction;
            }
        } else {
            if (!hasEnteredZone) {
                hasEnteredZone = true;
                logSuccess("✅ 已进入自己的区域！开始清理工作");
            }
            
            // 清理区域内最近的障碍物
            EntityID zoneRoad = findNearestBlockedRoadInZone(position);
            if (zoneRoad != null) {
                this.target = zoneRoad;
                retryCount = 0;
                
                int totalZoneRoads = myZoneRoads.size();
                int clearedCount = zoneClearedRoads.size();
                logInfo("区域内清理: " + zoneRoad + 
                        " (已清理 " + clearedCount + "/" + totalZoneRoads + " 条道路)");
                return this.actionExtClear.setTarget(this.target).calc().getAction();
            }
            
            // 区域内清理完毕，但全图还有障碍物，去其他区域帮忙
            if (isZoneFullyCleared() && hasAnyBlockadeGlobal()) {
                logInfo("区域内已清理完毕，全图还有 " + totalBlockades + " 个障碍物，去其他区域帮忙");
                return handleAssistOthers(position);
            }
        }
        
        // 检查救援通道
        EntityID rescueRoad = findNearestRescueRoute(position);
        if (rescueRoad != null) {
            this.target = rescueRoad;
            retryCount = 0;
            logInfo("救援通道清理: " + rescueRoad);
            return this.actionExtClear.setTarget(this.target).calc().getAction();
        }
        
        // 全图搜索障碍物（强制清理）
        EntityID globalRoad = findNearestBlockedRoadGlobal(position);
        if (globalRoad != null) {
            this.target = globalRoad;
            retryCount = 0;
            logInfo("全局清理: " + globalRoad + " (剩余障碍物: " + totalBlockades + ")");
            return this.actionExtClear.setTarget(this.target).calc().getAction();
        }
        
        // ========== 如果到这里还没有任务，说明没有障碍物了 ==========
        // 但为了保险，再次检查
        if (!hasAnyBlockadeGlobal()) {
            logInfo("✅ 全图清理完毕！前往避难所休息");
            return handleRest();
        }
        
        // 理论上不应该到这里，但以防万一
        logInfo("无法找到任务，但全图还有障碍物，等待...");
        return new ActionRest();
    }
    
    private Action tryEnterZone(EntityID position) {
        if (myZoneRoads == null || myZoneRoads.isEmpty()) return null;
        if (zoneEntryPoint == null) {
            zoneEntryPoint = findZoneEntryPoint();
        }
        if (zoneEntryPoint == null) return null;
        
        if (hasBlockadeOnPath(position, zoneEntryPoint)) {
            EntityID blockedRoad = findFirstBlockedRoadOnPath(position, zoneEntryPoint);
            if (blockedRoad != null) {
                this.target = blockedRoad;
                logInfo("通往区域的路径被阻塞，先清理: " + blockedRoad);
                return this.actionExtClear.setTarget(this.target).calc().getAction();
            }
        }
        
        if (position.getValue() != zoneEntryPoint.getValue()) {
            List<EntityID> path = getPath(position, zoneEntryPoint);
            if (path != null && !path.isEmpty()) {
                logInfo("前往区域入口: " + zoneEntryPoint);
                return new ActionMove(path);
            }
        }
        return null;
    }
    
    private Action handleAssistOthers(EntityID position) {
        EntityID assistRoad = findNearestBlockedRoadGlobal(position);
        if (assistRoad != null) {
            this.target = assistRoad;
            logInfo("协助清理其他区域: " + assistRoad);
            return this.actionExtClear.setTarget(this.target).calc().getAction();
        }
        
        // 如果没有找到障碍物，但全图还有障碍物（可能是路径规划失败）
        if (hasAnyBlockadeGlobal()) {
            logInfo("无法找到可达的障碍物，尝试重新规划");
            // 重置目标，下次重新搜索
            this.target = null;
            return handleAutonomy();
        }
        
        return handleRest();
    }
    
    private Action handleMoveToTarget(EntityID targetId) {
        EntityID position = this.agentInfo.getPosition();
        
        if (position.getValue() == targetId.getValue()) {
            return null;
        }
        
        List<EntityID> path = getPath(position, targetId);
        if (path != null && !path.isEmpty()) {
            return new ActionMove(path);
        }
        return null;
    }

    // ==================== 区域搜索方法 ====================
    
    private EntityID findNearestBlockedRoadInZone(EntityID position) {
        if (this.myZoneRoads == null || this.myZoneRoads.isEmpty()) {
            return null;
        }
        
        EntityID nearest = null;
        int minDistance = Integer.MAX_VALUE;
        
        for (EntityID roadId : this.myZoneRoads) {
            if (!hasBlockades(roadId)) continue;
            if (globalClearedRoads.contains(roadId)) continue;
            if (!isReachable(position, roadId)) continue;
            
            List<EntityID> path = getPath(position, roadId);
            if (path != null && path.size() < minDistance) {
                minDistance = path.size();
                nearest = roadId;
            }
        }
        return nearest;
    }
    
    private EntityID findNearestBlockedRoadGlobal(EntityID position) {
        if (this.allRoads == null || this.allRoads.isEmpty()) {
            return null;
        }
        
        EntityID nearest = null;
        int minDistance = Integer.MAX_VALUE;
        
        for (EntityID roadId : this.allRoads) {
            if (!hasBlockades(roadId)) continue;
            if (globalClearedRoads.contains(roadId)) continue;
            if (!isReachable(position, roadId)) continue;
            
            List<EntityID> path = getPath(position, roadId);
            if (path != null && path.size() < minDistance) {
                minDistance = path.size();
                nearest = roadId;
            }
        }
        return nearest;
    }
    
    private EntityID findNearestRescueRoute(EntityID position) {
        if (rescueRoutes.isEmpty()) return null;
        
        List<EntityID> toRemove = new ArrayList<>();
        for (EntityID roadId : rescueRoutes) {
            if (!hasBlockades(roadId)) {
                toRemove.add(roadId);
            }
        }
        rescueRoutes.removeAll(toRemove);
        rescueRoutePriority.keySet().removeAll(toRemove);
        
        if (rescueRoutes.isEmpty()) return null;
        
        List<EntityID> sortedRoutes = new ArrayList<>(rescueRoutes);
        sortedRoutes.sort((a, b) -> {
            int priorityA = rescueRoutePriority.getOrDefault(a, 0);
            int priorityB = rescueRoutePriority.getOrDefault(b, 0);
            if (priorityA != priorityB) {
                return Integer.compare(priorityB, priorityA);
            }
            int distA = getDistance(position, a);
            int distB = getDistance(position, b);
            return Integer.compare(distA, distB);
        });
        
        for (EntityID roadId : sortedRoutes) {
            if (isReachable(position, roadId)) {
                return roadId;
            }
        }
        return null;
    }
    
    private boolean hasBlockadeOnPath(EntityID from, EntityID to) {
        List<EntityID> path = getPath(from, to);
        if (path == null) return true;
        
        for (EntityID step : path) {
            if (hasBlockades(step)) {
                return true;
            }
        }
        return false;
    }
    
    private EntityID findFirstBlockedRoadOnPath(EntityID from, EntityID to) {
        List<EntityID> path = getPath(from, to);
        if (path == null) return null;
        
        for (EntityID step : path) {
            if (hasBlockades(step)) {
                return step;
            }
        }
        return null;
    }
    
    private boolean isZoneFullyCleared() {
        if (myZoneRoads == null || myZoneRoads.isEmpty()) return true;
        
        for (EntityID roadId : myZoneRoads) {
            if (hasBlockades(roadId)) {
                return false;
            }
        }
        return true;
    }

    // ==================== 辅助方法 ====================
    
    private boolean isCurrentTaskValid() {
        if (this.target == null) return false;
        return hasBlockades(this.target);
    }
    
    private boolean hasBlockades(EntityID roadId) {
        StandardEntity entity = this.worldInfo.getEntity(roadId);
        if (!(entity instanceof Road)) return false;
        
        Road road = (Road) entity;
        return road.isBlockadesDefined() && !road.getBlockades().isEmpty();
    }
    
    private boolean isReachable(EntityID from, EntityID to) {
        List<EntityID> path = getPath(from, to);
        return path != null && !path.isEmpty();
    }
    
    private List<EntityID> getPath(EntityID from, EntityID to) {
        if (this.pathPlanning == null) return null;
        return this.pathPlanning.getResult(from, to);
    }
    
    private List<EntityID> getPath(EntityID from, Collection<EntityID> targets) {
        if (this.pathPlanning == null) return null;
        this.pathPlanning.setFrom(from);
        this.pathPlanning.setDestination(targets);
        return this.pathPlanning.calc().getResult();
    }
    
    private int getDistance(EntityID from, EntityID to) {
        if (this.pathPlanning != null) {
            return (int) this.pathPlanning.getDistance(from, to);
        }
        StandardEntity fromEntity = this.worldInfo.getEntity(from);
        StandardEntity toEntity = this.worldInfo.getEntity(to);
        if (fromEntity != null && toEntity != null) {
            return this.worldInfo.getDistance(fromEntity, toEntity);
        }
        return Integer.MAX_VALUE;
    }
    
    private boolean isCommandCompleted() {
        switch (this.commandType) {
            case ACTION_REST:
                if (this.target == null) {
                    Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
                    return refuges.contains(this.agentInfo.getPosition());
                }
                return this.agentInfo.getPosition().getValue() == this.target.getValue();
                
            case ACTION_MOVE:
                return this.target == null || 
                       this.agentInfo.getPosition().getValue() == this.target.getValue();
                       
            case ACTION_CLEAR:
                if (this.target == null) return true;
                StandardEntity entity = this.worldInfo.getEntity(this.target);
                if (!(entity instanceof Road)) return true;
                Road road = (Road) entity;
                return !road.isBlockadesDefined() || road.getBlockades().isEmpty();
                
            case ACTION_AUTONOMY:
                if (this.target == null) return false;
                StandardEntity targetEntity = this.worldInfo.getEntity(this.target);
                if (targetEntity instanceof Road) {
                    Road targetRoad = (Road) targetEntity;
                    return !targetRoad.isBlockadesDefined() || targetRoad.getBlockades().isEmpty();
                }
                return true;
                
            default:
                return true;
        }
    }
    
    // ==================== 公共接口 ====================
    
    public void setRescueRoute(EntityID roadId, int priority) {
        this.rescueRoutes.add(roadId);
        this.rescueRoutePriority.put(roadId, priority);
    }
    
    public void setRescueRoutes(Map<EntityID, Integer> routes) {
        this.rescueRoutes.addAll(routes.keySet());
        this.rescueRoutePriority.putAll(routes);
    }

    // ==================== 日志方法 ====================
    
    private void logInfo(String msg) {
        System.err.println("[警车执行器] " + msg);
    }
    
    private void logSuccess(String msg) {
        System.err.println("✅ [警车执行器] " + msg);
    }
    
    private void logCommand(int action, EntityID target) {
        String actionName;
        switch (action) {
            case ACTION_REST: actionName = "休息"; break;
            case ACTION_MOVE: actionName = "移动"; break;
            case ACTION_CLEAR: actionName = "清理"; break;
            case ACTION_AUTONOMY: actionName = "自主"; break;
            default: actionName = "未知(" + action + ")"; break;
        }
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [警车执行器] 🚓 收到命令！                                   ║");
        System.err.println("║  警车 ID: " + this.agentInfo.getID());
        System.err.println("║  命令: " + actionName);
        System.err.println("║  目标: " + (target == null ? "无" : target));
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
    }

    // ==================== 生命周期方法 ====================
    
    @Override
    public CommandExecutor<CommandPolice> precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) return this;
        if (this.pathPlanning != null) this.pathPlanning.precompute(precomputeData);
        if (this.actionExtClear != null) this.actionExtClear.precompute(precomputeData);
        if (this.actionExtMove != null) this.actionExtMove.precompute(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor<CommandPolice> resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        if (this.pathPlanning != null) this.pathPlanning.resume(precomputeData);
        if (this.actionExtClear != null) this.actionExtClear.resume(precomputeData);
        if (this.actionExtMove != null) this.actionExtMove.resume(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor<CommandPolice> preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) return this;
        if (this.pathPlanning != null) this.pathPlanning.preparate();
        if (this.actionExtClear != null) this.actionExtClear.preparate();
        if (this.actionExtMove != null) this.actionExtMove.preparate();
        return this;
    }
}