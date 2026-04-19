package ZCWL_2026.module.complex;

import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import java.util.stream.Collectors;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class RoadDetector extends adf.core.component.module.complex.RoadDetector {

    // 待清理道路集合
    private Set<EntityID> targetRoads;
    // 高优先级道路（避难所周边深度搜索得到的）
    private Set<EntityID> refugeAreaRoads;
    // 被困建筑相关的道路
    private Set<EntityID> victimBuildingRoads;
    // ========== 救援单位视觉上报的道路 ==========
    private Set<EntityID> reportedVisionRoads;
    
    private PathPlanning pathPlanning;
    private EntityID result;

    // 已上报过被困平民的建筑（避免重复上报）
    private Set<EntityID> reportedVictimBuildings;
    // 已处理过的被困建筑（避免重复添加道路）
    private Set<EntityID> processedVictimBuildings;
    
    // ========== 锁信息内部类（记录锁定时间和警察ID） ==========
    private static class LockInfo {
        final EntityID policeId;
        final int lockTime;
        LockInfo(EntityID policeId, int lockTime) {
            this.policeId = policeId;
            this.lockTime = lockTime;
        }
    }
    
    // 记录每个警察锁定的道路（用于全局协调）
    private static Map<EntityID, LockInfo> lockedRoads = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int LOCK_EXPIRE_TIME = 66; // 超时步数
    private Set<EntityID> myLockedRoads; // 本警察锁定的道路
    
    // 搜索深度
    private static final int REFUGE_SEARCH_DEPTH = 4;
    
    // 日志控制
    private int lastLogTime = 0;
    private static final int LOG_INTERVAL = 10;

    public RoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        
        this.reportedVictimBuildings = new HashSet<>();
        this.processedVictimBuildings = new HashSet<>();
        this.targetRoads = new HashSet<>();
        this.refugeAreaRoads = new HashSet<>();
        this.victimBuildingRoads = new HashSet<>();
        this.reportedVisionRoads = new HashSet<>();
        this.myLockedRoads = new HashSet<>();
        
        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule("RoadDetector.PathPlanning", "ZCWL_2026.module.algorithm.PathPlanning");
                break;
        }
        registerModule(this.pathPlanning);
        this.result = null;
        
        //System.err.println("[RoadDetector] 警察 " + agentInfo.getID() + " 道路检测器已加载（优先级：避难所 > 被困建筑 > 视觉上报）");
    }

    // ==================== 道路状态判断方法 ====================
    
    private boolean isRoadDefined(EntityID roadId) {
        StandardEntity e = worldInfo.getEntity(roadId);
        if (e == null) return false;
        if (!(e instanceof Road)) return false;
        Road r = (Road) e;
        return r.isBlockadesDefined();
    }
    
    private boolean hasBlockades(EntityID roadId) {
        StandardEntity e = worldInfo.getEntity(roadId);
        if (e == null) return false;
        if (!(e instanceof Road)) return false;
        Road r = (Road) e;
        return r.isBlockadesDefined() && !r.getBlockades().isEmpty();
    }
    
    private boolean needsClearing(EntityID roadId) {
        StandardEntity e = worldInfo.getEntity(roadId);
        if (e == null) return false;
        if (!(e instanceof Road)) return false;
        Road r = (Road) e;
        if (!r.isBlockadesDefined()) {
            return true;
        }
        return !r.getBlockades().isEmpty();
    }

    // ==================== 添加道路的辅助方法 ====================
    
    private boolean tryAddRoad(EntityID roadId, Set<EntityID> sourceSet, String sourceName) {
        if (!needsClearing(roadId)) {
            return false;
        }
        if (sourceSet.add(roadId)) {
            targetRoads.add(roadId);
            return true;
        }
        return false;
    }

    // ==================== 生命周期方法 ====================

    @Override
    public RoadDetector calc() {
        try {
            if (this.result == null) {
                EntityID positionID = this.agentInfo.getPosition();
                EntityID myId = this.agentInfo.getID();
                
                // 输出三类道路剩余数量
                System.err.println("[RoadDetector] 警察 " + myId + " 道路状态: 避难所=" + refugeAreaRoads.size() + 
                                   ", 被困建筑=" + victimBuildingRoads.size() + 
                                   ", 视觉上报=" + reportedVisionRoads.size());
                
                // 如果当前位置已经是目标道路，直接返回
                if (this.targetRoads.contains(positionID)) {
                    this.result = positionID;
                    return this;
                }
                
                // 按优先级选择目标道路
                EntityID selectedRoad = selectTargetByPriority(positionID, myId);
                
                if (selectedRoad != null) {
                    this.result = selectedRoad;
                    // 锁定道路，记录当前时间
                    lockedRoads.put(selectedRoad, new LockInfo(myId, this.agentInfo.getTime()));
                    myLockedRoads.add(selectedRoad);
                    
                    double distance = worldInfo.getDistance(positionID, selectedRoad);
                   /*  System.err.println("[RoadDetector] 警察 " + myId + " 选择目标道路: " + selectedRoad + 
                                       "，距离=" + String.format("%.0f", distance) + "，已锁定");*/
                }
            }
        } catch (Exception e) {
            System.err.println("[RoadDetector] calc() 异常: " + e.getMessage());
            e.printStackTrace();
            this.result = null;
        }
        return this;
    }

    /**
     * 按优先级选择目标道路：避难所 > 被困建筑 > 视觉上报
     */
    private EntityID selectTargetByPriority(EntityID positionID, EntityID myId) {
        // 优先级1：避难所周边的道路
        EntityID selected = selectFromSet(positionID, myId, refugeAreaRoads);
        if (selected != null) {
            return selected;
        }
        
        // 优先级2：被困建筑周边的道路
        selected = selectFromSet(positionID, myId, victimBuildingRoads);
        if (selected != null) {
            return selected;
        }
        
        // 优先级3：救援单位视觉上报的道路
        selected = selectFromSet(positionID, myId, reportedVisionRoads);
        if (selected != null) {
            return selected;
        }
        
        return null;
    }

    /**
     * 从指定集合中选择最近的未被锁定的道路
     */
    private EntityID selectFromSet(EntityID positionID, EntityID myId, Set<EntityID> roadSet) {
        Set<EntityID> availableRoads = new HashSet<>(roadSet);
        availableRoads.removeIf(roadId -> {
            LockInfo info = lockedRoads.get(roadId);
            return info != null && !info.policeId.equals(myId);
        });
        
        if (availableRoads.isEmpty()) {
            return null;
        }
        
        this.pathPlanning.setFrom(positionID);
        this.pathPlanning.setDestination(availableRoads);
        List<EntityID> path = this.pathPlanning.calc().getResult();
        if (path != null && !path.isEmpty()) {
            return path.get(path.size() - 1);
        }
        return null;
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }

    @Override
    public RoadDetector precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) return this;
        return this;
    }

    @Override
    public RoadDetector resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        initTargetRoads();
        return this;
    }

    @Override
    public RoadDetector preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) return this;
        initTargetRoads();
        return this;
    }

    /**
     * 从避难所出发广度搜索深度为 REFUGE_SEARCH_DEPTH 的道路，只加入需要清理的
     */
    private void initTargetRoads() {
        this.targetRoads.clear();
        this.refugeAreaRoads.clear();
        
        Set<EntityID> visited = new HashSet<>();
        Queue<EntityID> queue = new LinkedList<>();
        
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE)) {
            if (e == null) continue;
            if (e instanceof Building) {
                Building refuge = (Building) e;
                for (EntityID nb : refuge.getNeighbours()) {
                    if (worldInfo.getEntity(nb) instanceof Road && !visited.contains(nb)) {
                        queue.add(nb);
                        visited.add(nb);
                    }
                }
            }
        }
        
        int depth = REFUGE_SEARCH_DEPTH;
        int totalAdded = 0;
        
        while (!queue.isEmpty() && depth-- > 0) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                EntityID roadId = queue.poll();
                Road road = (Road) worldInfo.getEntity(roadId);
                if (road == null) continue;
                
                if (needsClearing(roadId)) {
                    if (refugeAreaRoads.add(roadId)) {
                        targetRoads.add(roadId);
                        totalAdded++;
                    }
                }
                
                for (EntityID neighborId : road.getNeighbours()) {
                    if (worldInfo.getEntity(neighborId) instanceof Road && !visited.contains(neighborId)) {
                        visited.add(neighborId);
                        queue.add(neighborId);
                    }
                }
            }
        }
    }

    @Override
    public RoadDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;
        
        EntityID myId = this.agentInfo.getID();
        
        cleanCompletedRoads();
        cleanExpiredLocks();
        processVictimMessages(messageManager);
        processVisionReports(messageManager);
        checkCurrentTarget();
        processMessages(messageManager);
        
        int currentTime = this.agentInfo.getTime();
        if (currentTime - lastLogTime >= LOG_INTERVAL) {
            lastLogTime = currentTime;
        }
        
        return this;
    }

    private void cleanCompletedRoads() {
        Set<EntityID> completed = new HashSet<>();
        for (EntityID roadId : targetRoads) {
            StandardEntity e = worldInfo.getEntity(roadId);
            if (e == null) {
                completed.add(roadId);
                continue;
            }
            if (e instanceof Road) {
                Road r = (Road) e;
                if (r.isBlockadesDefined() && r.getBlockades().isEmpty()) {
                    completed.add(roadId);
                }
            } else {
                completed.add(roadId);
            }
        }
        
        if (!completed.isEmpty()) {
            for (EntityID roadId : completed) {
                targetRoads.remove(roadId);
                refugeAreaRoads.remove(roadId);
                victimBuildingRoads.remove(roadId);
                reportedVisionRoads.remove(roadId);
                myLockedRoads.remove(roadId);
                lockedRoads.remove(roadId);
            }
        }
    }

    private void cleanExpiredLocks() {
        int currentTime = this.agentInfo.getTime();
        Iterator<Map.Entry<EntityID, LockInfo>> iterator = lockedRoads.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<EntityID, LockInfo> entry = iterator.next();
            LockInfo info = entry.getValue();
            StandardEntity police = worldInfo.getEntity(info.policeId);
            if (police == null || (currentTime - info.lockTime) > LOCK_EXPIRE_TIME) {
                iterator.remove();
            }
        }
    }

    private void processVictimMessages(MessageManager messageManager) {
        for (CommunicationMessage msg : messageManager.getReceivedMessageList()) {
            EntityID buildingId = null;
            
            if (msg instanceof MessageCivilian) {
                MessageCivilian mc = (MessageCivilian) msg;
                if (mc.isBuriednessDefined() && mc.getBuriedness() > 0 && mc.isPositionDefined()) {
                    buildingId = mc.getPosition();
                }
            } else if (msg instanceof MessageFireBrigade) {
                MessageFireBrigade mfb = (MessageFireBrigade) msg;
                if (mfb.isBuriednessDefined() && mfb.getBuriedness() > 0 && mfb.isPositionDefined()) {
                    buildingId = mfb.getPosition();
                }
            } else if (msg instanceof MessagePoliceForce) {
                MessagePoliceForce mpf = (MessagePoliceForce) msg;
                if (mpf.isBuriednessDefined() && mpf.getBuriedness() > 0 && mpf.isPositionDefined()) {
                    buildingId = mpf.getPosition();
                }
            } else if (msg instanceof MessageAmbulanceTeam) {
                MessageAmbulanceTeam mat = (MessageAmbulanceTeam) msg;
                if (mat.isBuriednessDefined() && mat.getBuriedness() > 0 && mat.isPositionDefined()) {
                    buildingId = mat.getPosition();
                }
            }
            
            if (buildingId != null && !processedVictimBuildings.contains(buildingId)) {
                StandardEntity entity = worldInfo.getEntity(buildingId);
                if (entity == null) {
                    processedVictimBuildings.add(buildingId);
                    continue;
                }
                addRoadsForVictimBuilding(buildingId);
                processedVictimBuildings.add(buildingId);
            }
        }
    }

    /**
     * 为被困建筑添加周边道路：使用 BFS 深度搜索（深度 = REFUGE_SEARCH_DEPTH），
     * 将所有需要清理的道路加入 victimBuildingRoads。
     */
    private void addRoadsForVictimBuilding(EntityID buildingId) {
        StandardEntity entity = worldInfo.getEntity(buildingId);
        if (entity == null) return;
        if (!(entity instanceof Building)) return;
        Building building = (Building) entity;

        Set<EntityID> visited = new HashSet<>();
        Queue<EntityID> queue = new LinkedList<>();
        boolean hasDirectRoad = false;

        for (EntityID nb : building.getNeighbours()) {
            if (worldInfo.getEntity(nb) instanceof Road && !visited.contains(nb)) {
                hasDirectRoad = true;
                queue.add(nb);
                visited.add(nb);
            }
        }

        if (hasDirectRoad) {
            int depth = REFUGE_SEARCH_DEPTH;
            while (!queue.isEmpty() && depth-- > 0) {
                int size = queue.size();
                for (int i = 0; i < size; i++) {
                    EntityID roadId = queue.poll();
                    Road road = (Road) worldInfo.getEntity(roadId);
                    if (road == null) continue;

                    if (needsClearing(roadId)) {
                        tryAddRoad(roadId, victimBuildingRoads, "被困建筑深度" + (REFUGE_SEARCH_DEPTH - depth));
                    }

                    for (EntityID neighborId : road.getNeighbours()) {
                        if (worldInfo.getEntity(neighborId) instanceof Road && !visited.contains(neighborId)) {
                            visited.add(neighborId);
                            queue.add(neighborId);
                        }
                    }
                }
            }
        } else if (pathPlanning != null) {
            EntityID nearestRoad = findNearestRoadFromBuilding(buildingId);
            if (nearestRoad != null) {
                tryAddRoad(nearestRoad, victimBuildingRoads, "被困建筑最近");
            }
        }
    }

    private EntityID findNearestRoadFromBuilding(EntityID buildingId) {
        Collection<EntityID> allRoads = worldInfo.getEntityIDsOfType(ROAD);
        if (allRoads.isEmpty()) return null;
        
        pathPlanning.setFrom(buildingId);
        pathPlanning.setDestination(allRoads);
        List<EntityID> path = pathPlanning.calc().getResult();
        if (path != null && !path.isEmpty()) {
            for (EntityID step : path) {
                if (worldInfo.getEntity(step) instanceof Road) {
                    return step;
                }
            }
        }
        return null;
    }

    // ========== 处理救援单位视觉上报的道路 ==========
    private void processVisionReports(MessageManager messageManager) {
        for (CommunicationMessage msg : messageManager.getReceivedMessageList()) {
            if (msg instanceof MessageRoad) {
                MessageRoad mr = (MessageRoad) msg;
                Boolean passable = mr.isPassable();
                if (mr.isRadio() && passable != null && !passable) {
                    EntityID roadId = mr.getRoadID();
                    StandardEntity e = worldInfo.getEntity(roadId);
                    if (e == null || !(e instanceof Road)) continue;
                    
                    if (needsClearing(roadId)) {
                        if (reportedVisionRoads.add(roadId)) {
                            targetRoads.add(roadId);
                        }
                    }
                }
            }
        }
    }

    private void checkCurrentTarget() {
        if (this.result != null) {
            StandardEntity entity = this.worldInfo.getEntity(this.result);
            if (entity == null) {
                lockedRoads.remove(this.result);
                myLockedRoads.remove(this.result);
                targetRoads.remove(this.result);
                refugeAreaRoads.remove(this.result);
                victimBuildingRoads.remove(this.result);
                reportedVisionRoads.remove(this.result);
                this.result = null;
                return;
            }
            if (entity instanceof Road) {
                Road road = (Road) entity;
                if (road.isBlockadesDefined() && road.getBlockades().isEmpty()) {
                    lockedRoads.remove(this.result);
                    myLockedRoads.remove(this.result);
                    targetRoads.remove(this.result);
                    refugeAreaRoads.remove(this.result);
                    victimBuildingRoads.remove(this.result);
                    reportedVisionRoads.remove(this.result);
                    this.result = null;
                }
            }
        }
    }

    private void processMessages(MessageManager messageManager) {
        EntityID myId = this.agentInfo.getID();
        
        for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
            Class<? extends CommunicationMessage> messageClass = message.getClass();
            
            if (messageClass == MessageRoad.class) {
                MessageRoad mr = (MessageRoad) message;
                MessageUtil.reflectMessage(this.worldInfo, mr);
                if (mr.isPassable() != null && mr.isPassable()) {
                    EntityID roadId = mr.getRoadID();
                    targetRoads.remove(roadId);
                    refugeAreaRoads.remove(roadId);
                    victimBuildingRoads.remove(roadId);
                    reportedVisionRoads.remove(roadId);
                    lockedRoads.remove(roadId);
                    myLockedRoads.remove(roadId);
                }
            } else if (messageClass == MessagePoliceForce.class) {
                MessagePoliceForce mpf = (MessagePoliceForce) message;
                MessageUtil.reflectMessage(this.worldInfo, mpf);
                if (mpf.getAction() == MessagePoliceForce.ACTION_CLEAR && mpf.getTargetID() != null) {
                    EntityID targetRoad = mpf.getTargetID();
                    if (!mpf.getAgentID().equals(myId)) {
                        lockedRoads.putIfAbsent(targetRoad, new LockInfo(mpf.getAgentID(), this.agentInfo.getTime()));
                    }
                }
            }
        }
    }
}