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

    private Set<EntityID> targetRoads;
    private Set<EntityID> refugeAreaRoads;
    private Set<EntityID> victimBuildingRoads;
    
    // ========== 新增：消防车紧急任务触发的高优先级道路 ==========
    private Set<EntityID> fireRescueCriticalRoads;
    
    private Set<EntityID> exploredBuildings;
    private Map<EntityID, Integer> explorationCount;
    
    private PathPlanning pathPlanning;
    private EntityID result;

    private Set<EntityID> reportedVictimBuildings;
    private Set<EntityID> processedVictimBuildings;
    
    private static class LockInfo {
        final EntityID policeId;
        final int lockTime;
        LockInfo(EntityID policeId, int lockTime) {
            this.policeId = policeId;
            this.lockTime = lockTime;
        }
    }
    
    // ========== 修复：将静态锁改为实例变量，避免泄漏 ==========
    private Map<EntityID, LockInfo> lockedRoads;
    private static final int LOCK_EXPIRE_TIME = 66;
    private Set<EntityID> myLockedRoads;
    
    private static final int REFUGE_SEARCH_DEPTH = 6;
    
    private int lastLogTime = 0;
    private static final int LOG_INTERVAL = 10;

    private adf.core.component.module.algorithm.Clustering policeClustering;
    
    private static final int MAX_EXPLORATION_COUNT = 3;
    private Set<EntityID> warnedBuildings;

    public RoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        
        this.reportedVictimBuildings = new HashSet<>();
        this.processedVictimBuildings = new HashSet<>();
        this.targetRoads = new HashSet<>();
        this.refugeAreaRoads = new HashSet<>();
        this.victimBuildingRoads = new HashSet<>();
        this.fireRescueCriticalRoads = new HashSet<>();   // 新增
        this.myLockedRoads = new HashSet<>();
        this.exploredBuildings = new HashSet<>();
        this.explorationCount = new HashMap<>();
        this.warnedBuildings = new HashSet<>();
        this.lockedRoads = new HashMap<>();               // 实例变量
        
        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule("RoadDetector.PathPlanning", "ZCWL_2026.module.algorithm.PathPlanning");
                this.policeClustering = moduleManager.getModule("RoadDetector.PoliceClustering", "ZCWL_2026.module.algorithm.PoliceBalancedClustering");
                break;
        }
        registerModule(this.pathPlanning);
        if (this.policeClustering != null) {
            registerModule(this.policeClustering);
        }
        this.result = null;
        
        // System.err.println("[RoadDetector] 警察 " + agentInfo.getID() + " 道路检测器已加载（包含消防紧急道路），搜索深度=" + REFUGE_SEARCH_DEPTH);
    }

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

    @Override
    public RoadDetector calc() {
        try {
            if (this.result == null) {
                EntityID positionID = this.agentInfo.getPosition();
                EntityID myId = this.agentInfo.getID();
                
                if (this.targetRoads.contains(positionID)) {
                    this.result = positionID;
                    return this;
                }
                
                EntityID selectedRoad = selectTargetByPriority(positionID, myId);
                
                if (selectedRoad != null) {
                    this.result = selectedRoad;
                    lockedRoads.put(selectedRoad, new LockInfo(myId, this.agentInfo.getTime()));
                    myLockedRoads.add(selectedRoad);
                }
            }
        } catch (Exception e) {
            // System.err.println("[RoadDetector] calc() 异常: " + e.getMessage());
            // e.printStackTrace();
            this.result = null;
        }
        return this;
    }

    private EntityID selectTargetByPriority(EntityID positionID, EntityID myId) {
        // 1. 消防车紧急任务道路（最高优先级，允许跨簇借用）
        EntityID selected = selectFromSet(positionID, myId, fireRescueCriticalRoads, false);
        if (selected != null) {
            return selected;
        }

        // 2. 被困建筑周边道路
        selected = selectFromSet(positionID, myId, victimBuildingRoads, true);
        if (selected != null) {
            return selected;
        }
        
        // 3. 避难所周边道路
        selected = selectFromSet(positionID, myId, refugeAreaRoads, true);
        if (selected != null) {
            return selected;
        }
        
        return null;
    }

    private EntityID selectFromSet(EntityID positionID, EntityID myId, Set<EntityID> roadSet, boolean restrictToMyCluster) {
        Set<EntityID> availableRoads = new HashSet<>(roadSet);
        availableRoads.removeIf(roadId -> {
            LockInfo info = lockedRoads.get(roadId);
            return info != null && !info.policeId.equals(myId);
        });
        
        if (availableRoads.isEmpty()) {
            return null;
        }
        
        EntityID selected = null;
        
        if (policeClustering != null) {
            int myCluster = policeClustering.getClusterIndex(myId);
            if (myCluster >= 0) {
                Set<EntityID> myClusterRoads = new HashSet<>(availableRoads);
                myClusterRoads.removeIf(roadId -> policeClustering.getClusterIndex(roadId) != myCluster);
                
                if (!myClusterRoads.isEmpty()) {
                    selected = selectNearestFromSet(positionID, myClusterRoads);
                    if (selected != null) {
                        return selected;
                    }
                }
                
                if (restrictToMyCluster) {
                    Map<Integer, List<EntityID>> clusterToRoads = new HashMap<>();
                    for (EntityID roadId : availableRoads) {
                        int cluster = policeClustering.getClusterIndex(roadId);
                        if (cluster >= 0) {
                            clusterToRoads.computeIfAbsent(cluster, k -> new ArrayList<>()).add(roadId);
                        }
                    }
                    
                    int bestCluster = -1;
                    int maxCount = -1;
                    for (Map.Entry<Integer, List<EntityID>> entry : clusterToRoads.entrySet()) {
                        if (entry.getValue().size() > maxCount) {
                            maxCount = entry.getValue().size();
                            bestCluster = entry.getKey();
                        }
                    }
                    
                    if (bestCluster >= 0) {
                        Set<EntityID> borrowRoads = new HashSet<>(clusterToRoads.get(bestCluster));
                        selected = selectNearestFromSet(positionID, borrowRoads);
                        if (selected != null) {
                            // System.err.println("[RoadDetector] 警察 " + myId + " 本区无任务，从簇 " + bestCluster + " 借用道路 (任务数=" + maxCount + ")");
                            return selected;
                        }
                    }
                }
            }
        }
        
        return selectNearestFromSet(positionID, availableRoads);
    }
    
    private EntityID selectNearestFromSet(EntityID positionID, Set<EntityID> roadSet) {
        if (roadSet.isEmpty()) {
            return null;
        }
        this.pathPlanning.setFrom(positionID);
        this.pathPlanning.setDestination(roadSet);
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

    private void initTargetRoads() {
        this.targetRoads.clear();
        this.refugeAreaRoads.clear();
        
        Set<EntityID> visited = new HashSet<>();
        Queue<EntityID> queue = new LinkedList<>();
        
        int refugeCount = 0;
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE)) {
            if (e == null) continue;
            if (e instanceof Building) {
                refugeCount++;
                Building refuge = (Building) e;
                for (EntityID nb : refuge.getNeighbours()) {
                    if (worldInfo.getEntity(nb) instanceof Road && !visited.contains(nb)) {
                        queue.add(nb);
                        visited.add(nb);
                    }
                }
            }
        }
        
        // System.err.println("[RoadDetector] 地图中避难所数量: " + refugeCount + ", 搜索深度: " + REFUGE_SEARCH_DEPTH);
        
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
        
        // System.err.println("[RoadDetector] 初始化完成: 避难所周边道路=" + refugeAreaRoads.size() + " (新增=" + totalAdded + ")");
    }

    @Override
    public RoadDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;
        
        EntityID myId = this.agentInfo.getID();
        
        cleanCompletedRoads();
        cleanExpiredLocks();
        processVictimMessages(messageManager);
        checkCurrentTarget();
        processMessages(messageManager);

        // ========== 新增：处理消防车紧急动作，生成动态关键道路 ==========
        for (CommunicationMessage msg : messageManager.getReceivedMessageList()) {
            if (msg instanceof MessageFireBrigade) {
                MessageFireBrigade mfb = (MessageFireBrigade) msg;
                if (mfb.getAction() == MessageFireBrigade.ACTION_EXTINGUISH ||
                    mfb.getAction() == MessageFireBrigade.ACTION_RESCUE) {
                    EntityID targetId = mfb.getTargetID();
                    if (targetId != null) {
                        addFireRescueRoadsForTarget(targetId);
                    }
                }
            }
        }
        
        int currentTime = this.agentInfo.getTime();
        if (currentTime - lastLogTime >= LOG_INTERVAL) {
            lastLogTime = currentTime;
        }
        
        return this;
    }

        private void addFireRescueRoadsForTarget(EntityID targetId) {
        StandardEntity targetEntity = worldInfo.getEntity(targetId);
        if (targetEntity == null) return;

        // 处理目标是 Human 的情况：获取他所在的建筑
        if (targetEntity instanceof Human) {
            Human human = (Human) targetEntity;
            if (human.isPositionDefined()) {
                EntityID pos = human.getPosition();
                StandardEntity posEntity = worldInfo.getEntity(pos);
                if (posEntity instanceof Building) {
                    // 为该建筑添加相邻阻塞道路
                    Building building = (Building) posEntity;
                    for (EntityID neighborId : building.getNeighbours()) {
                        StandardEntity neighbor = worldInfo.getEntity(neighborId);
                        if (neighbor instanceof Road && needsClearing(neighborId)) {
                            fireRescueCriticalRoads.add(neighborId);
                            targetRoads.add(neighborId);
                        }
                    }
                }
            }
            return;
        }

        if (targetEntity instanceof Building) {
            Building building = (Building) targetEntity;
            for (EntityID neighborId : building.getNeighbours()) {
                StandardEntity neighbor = worldInfo.getEntity(neighborId);
                if (neighbor instanceof Road) {
                    if (needsClearing(neighborId)) {
                        fireRescueCriticalRoads.add(neighborId);
                        targetRoads.add(neighborId);
                    }
                }
            }
        } else if (targetEntity instanceof Road) {
            if (needsClearing(targetId)) {
                fireRescueCriticalRoads.add(targetId);
                targetRoads.add(targetId);
            }
        }
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
                fireRescueCriticalRoads.remove(roadId);
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

    private void checkCurrentTarget() {
        if (this.result != null) {
            StandardEntity entity = this.worldInfo.getEntity(this.result);
            if (entity == null) {
                lockedRoads.remove(this.result);
                myLockedRoads.remove(this.result);
                targetRoads.remove(this.result);
                refugeAreaRoads.remove(this.result);
                victimBuildingRoads.remove(this.result);
                fireRescueCriticalRoads.remove(this.result);
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
                    fireRescueCriticalRoads.remove(this.result);
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
                    fireRescueCriticalRoads.remove(roadId);
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
    
    public void markBuildingExplored(EntityID buildingId) {
        if (buildingId == null) return;
        
        if (exploredBuildings.contains(buildingId)) {
            int count = explorationCount.getOrDefault(buildingId, 0) + 1;
            explorationCount.put(buildingId, count);
            
            if (count >= MAX_EXPLORATION_COUNT && !warnedBuildings.contains(buildingId)) {
                // System.err.println("[RoadDetector] 警告：警察 " + agentInfo.getID() + 
                                   // " 重复探索建筑 " + buildingId + " " + count + " 次");
                warnedBuildings.add(buildingId);
            }
        } else {
            exploredBuildings.add(buildingId);
            explorationCount.put(buildingId, 1);
        }
    }
    
    public boolean isBuildingExplored(EntityID buildingId) {
        return exploredBuildings.contains(buildingId);
    }
}