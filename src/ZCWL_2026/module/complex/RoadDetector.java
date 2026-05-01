package ZCWL_2026.module.complex;

import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
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
    private Set<EntityID> fireRescueCriticalRoads;
    private Set<EntityID> knownBlockedRoads;

    private PathPlanning pathPlanning;
    private EntityID result;

    private Set<EntityID> processedVictimBuildings;

    private static class LockInfo {
        final EntityID policeId;
        final int lockTime;
        LockInfo(EntityID policeId, int lockTime) {
            this.policeId = policeId;
            this.lockTime = lockTime;
        }
    }

    private Map<EntityID, LockInfo> lockedRoads;
    private static final int LOCK_EXPIRE_TIME = 66;

    private static final int REFUGE_SEARCH_DEPTH = 6;
    private int lastLogTime = 0;
    private static final int LOG_INTERVAL = 5;

    private adf.core.component.module.algorithm.Clustering policeClustering;
    private boolean amIBuried;
    private Set<Integer> myResponsibleClusters;

    // ========== 新增：全局帮助模式相关字段 ==========
    private boolean isGlobalHelping = false;         // 是否处于全局帮助模式
    private EntityID globalTarget = null;            // 全局帮助模式下的当前目标
    private int lastGlobalScanTime = 0;
    private static final int GLOBAL_SCAN_INTERVAL = 10; // 全局模式下，每10步检测本地是否有新任务

    public RoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                        ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        
        this.processedVictimBuildings = new HashSet<>();
        this.targetRoads = new HashSet<>();
        this.refugeAreaRoads = new HashSet<>();
        this.victimBuildingRoads = new HashSet<>();
        this.fireRescueCriticalRoads = new HashSet<>();
        this.knownBlockedRoads = new HashSet<>();
        this.lockedRoads = new HashMap<>();
        this.amIBuried = false;
        this.myResponsibleClusters = new HashSet<>();

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
        this.isGlobalHelping = false;    // 初始化
    }

    // ---------- 掩埋状态判断 ----------
    private boolean isBuried() {
        StandardEntity me = this.agentInfo.me();
        if (me instanceof PoliceForce) {
            PoliceForce police = (PoliceForce) me;
            return police.isBuriednessDefined() && police.getBuriedness() > 0;
        }
        return false;
    }

    private boolean isPoliceBuried(EntityID policeId) {
        if (policeId == null) return false;
        StandardEntity e = worldInfo.getEntity(policeId);
        if (e instanceof PoliceForce) {
            PoliceForce p = (PoliceForce) e;
            return p.isBuriednessDefined() && p.getBuriedness() > 0;
        }
        return false;
    }

    private int getMyClusterIndex() {
        if (policeClustering == null) return -1;
        policeClustering.calc();
        return policeClustering.getClusterIndex(this.agentInfo.getID());
    }

    /**
     * 重新计算当前警察应该负责的所有集群（自己集群 + 接管的掩埋警察集群）
     */
    private void recomputeResponsibleClusters() {
        myResponsibleClusters.clear();
        if (policeClustering == null) return;

        int myIdx = getMyClusterIndex();
        if (myIdx >= 0) {
            myResponsibleClusters.add(myIdx);
        }

        // 收集所有警察及其掩埋状态
        List<EntityID> allPolice = new ArrayList<>();
        Map<EntityID, Integer> policeCluster = new HashMap<>();
        for (StandardEntity e : worldInfo.getEntitiesOfType(POLICE_FORCE)) {
            EntityID id = e.getID();
            int c = policeClustering.getClusterIndex(id);
            if (c >= 0) {
                allPolice.add(id);
                policeCluster.put(id, c);
            }
        }

        // 可移动警察列表（未被掩埋）
        List<EntityID> movablePolice = allPolice.stream()
                .filter(id -> !isPoliceBuried(id))
                .sorted(Comparator.comparingInt(EntityID::getValue))
                .collect(Collectors.toList());

        if (movablePolice.isEmpty()) {
            System.err.println("[RoadDetector] 警察 " + agentInfo.getID().getValue() + " 无任何可移动警察，接管失败");
            return;
        }

        int numClusters = policeClustering.getClusterNumber();
        int takenOver = 0;
        for (int cluster = 0; cluster < numClusters; cluster++) {
            EntityID owner = getPoliceForCluster(cluster);
            if (owner == null) continue;
            if (isPoliceBuried(owner)) {
                int receiverIdx = cluster % movablePolice.size();
                EntityID receiver = movablePolice.get(receiverIdx);
                if (receiver.equals(this.agentInfo.getID())) {
                    myResponsibleClusters.add(cluster);
                    takenOver++;
                    System.err.println("[RoadDetector] 警察 " + receiver.getValue() +
                            " 接管集群 " + cluster + " (原警察 " + owner.getValue() + " 被掩埋)");
                }
            }
        }
        if (takenOver > 0) {
            System.err.println("[RoadDetector] 警察 " + agentInfo.getID().getValue() +
                    " 共接管 " + takenOver + " 个集群");
        }
    }

    private EntityID getPoliceForCluster(int clusterIndex) {
        for (StandardEntity e : worldInfo.getEntitiesOfType(POLICE_FORCE)) {
            EntityID id = e.getID();
            if (policeClustering.getClusterIndex(id) == clusterIndex) {
                return id;
            }
        }
        return null;
    }

    /**
     * 判断一条道路是否在当前警察的负责区域集合内
     */
    private boolean isInMyResponsibleArea(EntityID roadId) {
        if (amIBuried) return true;   // 自己被埋时不限制区域（但通常无法移动）
        int roadCluster = policeClustering.getClusterIndex(roadId);
        return roadCluster >= 0 && myResponsibleClusters.contains(roadCluster);
    }

    // ---------- 道路状态 ----------
    private boolean needsClearing(EntityID roadId) {
        StandardEntity e = worldInfo.getEntity(roadId);
        if (e == null) return false;
        if (!(e instanceof Road)) return false;
        Road r = (Road) e;
        if (!r.isBlockadesDefined()) return true;
        return !r.getBlockades().isEmpty();
    }

    // ---------- 核心选择 ----------
    @Override
    public RoadDetector calc() {
        try {
            if (this.result == null) {
                EntityID positionID = this.agentInfo.getPosition();
                EntityID myId = this.agentInfo.getID();

                // 如果当前道路仍需清理，优先处理
                if (this.targetRoads.contains(positionID)) {
                    this.result = positionID;
                    // 如果在全局模式，不必退出，因为本地任务还在，但targetRoads非空说明有任务
                    isGlobalHelping = false;
                    return this;
                }

                // 原有：优先处理本地任务
                EntityID selected = selectTargetByPriority(positionID, myId);
                if (selected != null) {
                    this.result = selected;
                    lockedRoads.put(selected, new LockInfo(myId, this.agentInfo.getTime()));
                    // 有本地任务，退出全局帮助模式
                    if (isGlobalHelping) {
                        isGlobalHelping = false;
                        globalTarget = null;
                        System.err.println("[RoadDetector] 警察 " + myId.getValue() +
                                " 本地出现新任务，退出全局帮助模式，回归本地职责");
                    }
                    return this;
                }

                // ========== 新增：本地任务全部完成，进入全局帮助模式 ==========
                // 条件：所有任务集合(targetRoads)为空，并且当前未处于全局帮助模式
                if (targetRoads.isEmpty() && !isGlobalHelping) {
                    isGlobalHelping = true;
                    globalTarget = null;
                    System.err.println("[RoadDetector] 警察 " + myId.getValue() +
                            " ⭐ 本地集群任务清空，开启全局帮助模式！");
                }

                // 在全局帮助模式下，选择全局目标
                if (isGlobalHelping) {
                    selected = selectGlobalTarget(positionID, myId);
                    if (selected != null) {
                        this.result = selected;
                        lockedRoads.put(selected, new LockInfo(myId, this.agentInfo.getTime()));
                        globalTarget = selected;
                        System.err.println("[RoadDetector] 警察 " + myId.getValue() +
                                " 🌍 全局帮助模式选择目标: 道路 " + selected.getValue() +
                                " (距离=" + getDistance(positionID, selected) + ")");
                        return this;
                    } else {
                        // 整个地图都没有需要清理的道路了，退出帮助模式
                        if (isGlobalHelping) {
                            isGlobalHelping = false;
                            System.err.println("[RoadDetector] 警察 " + myId.getValue() +
                                    " 全局帮助模式未找到任何需要清理的道路，退出帮助模式");
                        }
                    }
                }
            }
        } catch (Exception e) {
            this.result = null;
        }
        return this;
    }

    /**
     * 新增：选择全局帮助目标
     * 从地图上所有不属于自己负责区域且需要清理的道路中选择最近的
     */
    private EntityID selectGlobalTarget(EntityID positionID, EntityID myId) {
        Set<EntityID> available = new HashSet<>();
        for (StandardEntity e : worldInfo.getEntitiesOfType(ROAD)) {
            EntityID rid = e.getID();
            // 排除自己负责区域内的道路（这些已经由本地逻辑处理）
            if (isInMyResponsibleArea(rid)) continue;
            // 只处理需要清理的道路
            if (!needsClearing(rid)) continue;
            // 排除已经被其他警察锁定的道路
            LockInfo lock = lockedRoads.get(rid);
            if (lock != null && !lock.policeId.equals(myId)) continue;
            available.add(rid);
        }
        if (available.isEmpty()) return null;
        return selectNearestFromSet(positionID, available);
    }

    private EntityID selectTargetByPriority(EntityID positionID, EntityID myId) {
        EntityID selected = selectFromSet(positionID, refugeAreaRoads);
        if (selected != null) return selected;
        selected = selectFromSet(positionID, fireRescueCriticalRoads);
        if (selected != null) return selected;
        selected = selectFromSet(positionID, victimBuildingRoads);
        if (selected != null) return selected;
        selected = selectFromSet(positionID, knownBlockedRoads);
        return selected;
    }

    private EntityID selectFromSet(EntityID positionID, Set<EntityID> roadSet) {
        Set<EntityID> available = new HashSet<>(roadSet);
        // 排除已被其他警察锁定的道路
        available.removeIf(rid -> {
            LockInfo lock = lockedRoads.get(rid);
            return lock != null && !lock.policeId.equals(this.agentInfo.getID());
        });
        if (available.isEmpty()) return null;
        return selectNearestFromSet(positionID, available);
    }

    private EntityID selectNearestFromSet(EntityID positionID, Set<EntityID> roadSet) {
        if (roadSet.isEmpty()) return null;
        this.pathPlanning.setFrom(positionID);
        this.pathPlanning.setDestination(roadSet);
        List<EntityID> path = this.pathPlanning.calc().getResult();
        if (path != null && !path.isEmpty()) {
            return path.get(path.size() - 1);
        }
        return null;
    }

    @Override
    public EntityID getTarget() { return this.result; }

    // ---------- 生命周期 ----------
    @Override
    public RoadDetector resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        this.amIBuried = isBuried();
        recomputeResponsibleClusters();
        initTargetRoads();
        return this;
    }

    @Override
    public RoadDetector preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) return this;
        this.amIBuried = isBuried();
        recomputeResponsibleClusters();
        initTargetRoads();
        return this;
    }

    private void initTargetRoads() {
        this.targetRoads.clear();
        this.refugeAreaRoads.clear();
        this.fireRescueCriticalRoads.clear();
        this.victimBuildingRoads.clear();
        this.knownBlockedRoads.clear();
        this.isGlobalHelping = false;   // 重置全局模式

        // 收集避难所周边道路（仅负责区域内的）
        Set<EntityID> visited = new HashSet<>();
        Queue<EntityID> queue = new LinkedList<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE)) {
            if (e instanceof Building) {
                Building refuge = (Building) e;
                for (EntityID nb : refuge.getNeighbours()) {
                    if (worldInfo.getEntity(nb) instanceof Road && !visited.contains(nb)) {
                        if (isInMyResponsibleArea(nb)) {
                            queue.add(nb);
                            visited.add(nb);
                        }
                    }
                }
            }
        }
        int depth = REFUGE_SEARCH_DEPTH;
        while (!queue.isEmpty() && depth-- > 0) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                EntityID roadId = queue.poll();
                Road road = (Road) worldInfo.getEntity(roadId);
                if (road == null) continue;
                if (needsClearing(roadId)) {
                    refugeAreaRoads.add(roadId);
                    targetRoads.add(roadId);
                }
                for (EntityID neighborId : road.getNeighbours()) {
                    if (worldInfo.getEntity(neighborId) instanceof Road && !visited.contains(neighborId)) {
                        if (isInMyResponsibleArea(neighborId)) {
                            visited.add(neighborId);
                            queue.add(neighborId);
                        }
                    }
                }
            }
        }

        // 兜底：负责区域内所有需要清理的道路
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(ROAD)) {
            if (e instanceof Road) {
                EntityID rid = e.getID();
                if (isInMyResponsibleArea(rid) && needsClearing(rid)) {
                    knownBlockedRoads.add(rid);
                    targetRoads.add(rid);
                }
            }
        }

        System.err.println("[RoadDetector] 警察 " + agentInfo.getID().getValue() +
                " 初始化: 负责集群=" + myResponsibleClusters +
                " 避难所=" + refugeAreaRoads.size() +
                " 兜底=" + knownBlockedRoads.size() +
                " 总=" + targetRoads.size() +
                " 埋=" + amIBuried);
    }

    @Override
    public RoadDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;

        this.amIBuried = isBuried();
        Set<Integer> oldClusters = new HashSet<>(myResponsibleClusters);
        recomputeResponsibleClusters();

        // 如果负责区域发生变化，补充新区域的道路任务
        if (!myResponsibleClusters.equals(oldClusters)) {
            Set<Integer> newClusters = new HashSet<>(myResponsibleClusters);
            newClusters.removeAll(oldClusters);
            if (!newClusters.isEmpty()) {
                addRoadsFromNewClusters(newClusters);
                // 如果新集群带来了任务，立即退出全局模式
                if (!targetRoads.isEmpty() && isGlobalHelping) {
                    isGlobalHelping = false;
                    globalTarget = null;
                    System.err.println("[RoadDetector] 警察 " + agentInfo.getID().getValue() +
                            " 因接管新集群出现任务，退出全局帮助模式");
                }
            }
        }

        cleanCompletedRoads();
        cleanExpiredLocks();
        processVictimMessages(messageManager);
        checkCurrentTarget();
        processMessages(messageManager);

        // 全局帮助模式下，定期检查本地是否出现新任务
        if (isGlobalHelping && (agentInfo.getTime() - lastGlobalScanTime >= GLOBAL_SCAN_INTERVAL)) {
            lastGlobalScanTime = agentInfo.getTime();
            if (hasLocalTask()) {
                isGlobalHelping = false;
                globalTarget = null;
                System.err.println("[RoadDetector] 警察 " + agentInfo.getID().getValue() +
                        " 🔔 检测到本地新任务，退出全局帮助模式");
            }
        }

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
            System.err.println("[RoadDetector] 警察 " + agentInfo.getID().getValue() +
                    " 负责集群=" + myResponsibleClusters +
                    " 避难所=" + refugeAreaRoads.size() +
                    " 消防=" + fireRescueCriticalRoads.size() +
                    " 受害=" + victimBuildingRoads.size() +
                    " 兜底=" + knownBlockedRoads.size() +
                    " 总=" + targetRoads.size() +
                    " 埋=" + amIBuried +
                    (isGlobalHelping ? " 🌍[全局帮助]" : ""));
        }
        return this;
    }

    /**
     * 新增：检测本地是否出现了需要清理的道路
     */
    private boolean hasLocalTask() {
        for (EntityID rid : knownBlockedRoads) {
            if (needsClearing(rid) && isInMyResponsibleArea(rid)) return true;
        }
        for (EntityID rid : refugeAreaRoads) {
            if (needsClearing(rid) && isInMyResponsibleArea(rid)) return true;
        }
        for (EntityID rid : fireRescueCriticalRoads) {
            if (needsClearing(rid) && isInMyResponsibleArea(rid)) return true;
        }
        for (EntityID rid : victimBuildingRoads) {
            if (needsClearing(rid) && isInMyResponsibleArea(rid)) return true;
        }
        return false;
    }

    /**
     * 将新接管的集群中的道路任务补充到当前任务池中。
     */
    private void addRoadsFromNewClusters(Set<Integer> newClusters) {
        int addedRefuge = 0;
        int addedBlocked = 0;

        // 1) 避难所周边道路
        Set<EntityID> visited = new HashSet<>();
        Queue<EntityID> queue = new LinkedList<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE)) {
            if (e instanceof Building) {
                Building refuge = (Building) e;
                for (EntityID nb : refuge.getNeighbours()) {
                    if (worldInfo.getEntity(nb) instanceof Road && !visited.contains(nb)) {
                        int roadCluster = policeClustering.getClusterIndex(nb);
                        if (newClusters.contains(roadCluster)) {
                            queue.add(nb);
                            visited.add(nb);
                        }
                    }
                }
            }
        }
        int depth = REFUGE_SEARCH_DEPTH;
        while (!queue.isEmpty() && depth-- > 0) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                EntityID roadId = queue.poll();
                Road road = (Road) worldInfo.getEntity(roadId);
                if (road == null) continue;
                if (needsClearing(roadId) && refugeAreaRoads.add(roadId)) {
                    targetRoads.add(roadId);
                    addedRefuge++;
                }
                for (EntityID neighborId : road.getNeighbours()) {
                    if (worldInfo.getEntity(neighborId) instanceof Road && !visited.contains(neighborId)) {
                        int nbCluster = policeClustering.getClusterIndex(neighborId);
                        if (newClusters.contains(nbCluster)) {
                            visited.add(neighborId);
                            queue.add(neighborId);
                        }
                    }
                }
            }
        }

        // 2) 兜底道路：新集群中所有需要清理的道路
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(ROAD)) {
            if (e instanceof Road) {
                EntityID rid = e.getID();
                int roadCluster = policeClustering.getClusterIndex(rid);
                if (newClusters.contains(roadCluster) && needsClearing(rid)) {
                    if (knownBlockedRoads.add(rid)) {
                        targetRoads.add(rid);
                        addedBlocked++;
                    }
                }
            }
        }

        System.err.println("[RoadDetector] 警察 " + agentInfo.getID().getValue() +
                " 补充新集群 " + newClusters + " 任务: 避难所 +" + addedRefuge + " 条, 兜底 +" + addedBlocked + " 条");
    }

    private void addFireRescueRoadsForTarget(EntityID targetId) {
        StandardEntity targetEntity = worldInfo.getEntity(targetId);
        if (targetEntity == null) return;

        if (targetEntity instanceof Human) {
            Human human = (Human) targetEntity;
            if (human.isPositionDefined()) {
                EntityID pos = human.getPosition();
                StandardEntity posEntity = worldInfo.getEntity(pos);
                if (posEntity instanceof Building) {
                    addBuildingRoadsIfResponsible((Building) posEntity);
                }
            }
        } else if (targetEntity instanceof Building) {
            addBuildingRoadsIfResponsible((Building) targetEntity);
        } else if (targetEntity instanceof Road) {
            if (needsClearing(targetId) && isInMyResponsibleArea(targetId)) {
                fireRescueCriticalRoads.add(targetId);
                targetRoads.add(targetId);
            }
        }
    }

    private void addBuildingRoadsIfResponsible(Building building) {
        for (EntityID neighborId : building.getNeighbours()) {
            StandardEntity neighbor = worldInfo.getEntity(neighborId);
            if (neighbor instanceof Road && needsClearing(neighborId)) {
                if (isInMyResponsibleArea(neighborId)) {
                    fireRescueCriticalRoads.add(neighborId);
                    targetRoads.add(neighborId);
                }
            }
        }
    }

    private void processVictimMessages(MessageManager messageManager) {
        for (CommunicationMessage msg : messageManager.getReceivedMessageList()) {
            EntityID buildingId = null;
            if (msg instanceof MessageCivilian) {
                MessageCivilian mc = (MessageCivilian) msg;
                if (mc.isBuriednessDefined() && mc.getBuriedness() > 0 && mc.isPositionDefined())
                    buildingId = mc.getPosition();
            } else if (msg instanceof MessageFireBrigade) {
                MessageFireBrigade mfb = (MessageFireBrigade) msg;
                if (mfb.isBuriednessDefined() && mfb.getBuriedness() > 0 && mfb.isPositionDefined())
                    buildingId = mfb.getPosition();
            } else if (msg instanceof MessagePoliceForce) {
                MessagePoliceForce mpf = (MessagePoliceForce) msg;
                if (mpf.isBuriednessDefined() && mpf.getBuriedness() > 0 && mpf.isPositionDefined())
                    buildingId = mpf.getPosition();
            } else if (msg instanceof MessageAmbulanceTeam) {
                MessageAmbulanceTeam mat = (MessageAmbulanceTeam) msg;
                if (mat.isBuriednessDefined() && mat.getBuriedness() > 0 && mat.isPositionDefined())
                    buildingId = mat.getPosition();
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
        if (!isInMyResponsibleArea(buildingId)) return;
        StandardEntity entity = worldInfo.getEntity(buildingId);
        if (!(entity instanceof Building)) return;
        Building building = (Building) entity;

        Set<EntityID> visited = new HashSet<>();
        Queue<EntityID> queue = new LinkedList<>();
        for (EntityID nb : building.getNeighbours()) {
            if (worldInfo.getEntity(nb) instanceof Road && !visited.contains(nb)) {
                if (isInMyResponsibleArea(nb)) {
                    queue.add(nb);
                    visited.add(nb);
                }
            }
        }
        int depth = REFUGE_SEARCH_DEPTH;
        while (!queue.isEmpty() && depth-- > 0) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                EntityID roadId = queue.poll();
                Road road = (Road) worldInfo.getEntity(roadId);
                if (road == null) continue;
                if (needsClearing(roadId)) {
                    victimBuildingRoads.add(roadId);
                    targetRoads.add(roadId);
                }
                for (EntityID neighborId : road.getNeighbours()) {
                    if (worldInfo.getEntity(neighborId) instanceof Road && !visited.contains(neighborId)) {
                        if (isInMyResponsibleArea(neighborId)) {
                            visited.add(neighborId);
                            queue.add(neighborId);
                        }
                    }
                }
            }
        }
    }

    private void cleanCompletedRoads() {
        Set<EntityID> completed = new HashSet<>();
        for (EntityID roadId : targetRoads) {
            StandardEntity e = worldInfo.getEntity(roadId);
            if (e == null || !(e instanceof Road)) {
                completed.add(roadId);
            } else {
                Road r = (Road) e;
                if (r.isBlockadesDefined() && r.getBlockades().isEmpty()) {
                    completed.add(roadId);
                }
            }
        }
        if (!completed.isEmpty()) {
            targetRoads.removeAll(completed);
            refugeAreaRoads.removeAll(completed);
            victimBuildingRoads.removeAll(completed);
            fireRescueCriticalRoads.removeAll(completed);
            knownBlockedRoads.removeAll(completed);
        }
    }

    private void cleanExpiredLocks() {
        int currentTime = this.agentInfo.getTime();
        lockedRoads.entrySet().removeIf(entry -> {
            LockInfo info = entry.getValue();
            StandardEntity police = worldInfo.getEntity(info.policeId);
            return police == null || (currentTime - info.lockTime) > LOCK_EXPIRE_TIME;
        });
    }

    private void checkCurrentTarget() {
        if (this.result != null) {
            StandardEntity entity = this.worldInfo.getEntity(this.result);
            if (entity == null || !(entity instanceof Road)) {
                clearResultFromSets();
            } else {
                Road road = (Road) entity;
                if (road.isBlockadesDefined() && road.getBlockades().isEmpty()) {
                    clearResultFromSets();
                }
            }
        }
    }

    private void clearResultFromSets() {
        if (this.result != null) {
            targetRoads.remove(this.result);
            refugeAreaRoads.remove(this.result);
            victimBuildingRoads.remove(this.result);
            fireRescueCriticalRoads.remove(this.result);
            knownBlockedRoads.remove(this.result);
            lockedRoads.remove(this.result);
            // 如果全局帮助模式下完成目标，重置globalTarget以便下次选取新的
            if (isGlobalHelping && this.result.equals(globalTarget)) {
                globalTarget = null;
            }
            this.result = null;
        }
    }

    private void processMessages(MessageManager messageManager) {
        EntityID myId = this.agentInfo.getID();
        for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
            if (message instanceof MessageRoad) {
                MessageRoad mr = (MessageRoad) message;
                MessageUtil.reflectMessage(this.worldInfo, mr);
                if (mr.isPassable() != null && mr.isPassable()) {
                    EntityID roadId = mr.getRoadID();
                    targetRoads.remove(roadId);
                    refugeAreaRoads.remove(roadId);
                    victimBuildingRoads.remove(roadId);
                    fireRescueCriticalRoads.remove(roadId);
                    knownBlockedRoads.remove(roadId);
                    lockedRoads.remove(roadId);
                }
            } else if (message instanceof MessagePoliceForce) {
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

    // 辅助方法：计算两点间的距离
    private double getDistance(EntityID from, EntityID to) {
        if (pathPlanning != null) {
            return pathPlanning.getDistance(from, to);
        }
        return worldInfo.getDistance(from, to);
    }
}