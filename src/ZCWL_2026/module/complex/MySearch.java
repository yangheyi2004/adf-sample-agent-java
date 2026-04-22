package ZCWL_2026.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageBuilding;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.Search;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;

/**
 * 搜索模块 - 只负责搜索未探索建筑
 * 
 * 策略：
 * - 消防/救护：优先探索本区域内建筑（SampleKMeans聚类）
 * - 警察：优先探索本簇道路连通区域内的建筑（PoliceBalancedClustering）
 */
public class MySearch extends Search {
    
    // ==================== 核心组件 ====================
    private PathPlanning pathPlanning;
    private Clustering clustering;                  // SampleKMeans (消防/救护)
    private Clustering policeClustering;            // PoliceBalancedClustering (警察)
    private StandardEntityURN agentType;
    
    // ==================== 建筑搜索相关 ====================
    private EntityID result;
    private Set<EntityID> unsearchedBuildings;
    private Set<EntityID> searchedBuildings;
    
    // ==================== 区域探索相关 ====================
    private Set<EntityID> zoneBuildings;
    private Set<EntityID> zoneUnsearched;
    private boolean zoneCompleted;
    private int zoneTotalCount;
    
    // ==================== BFS 深度配置 ====================
    private static final int POLICE_BFS_DEPTH = 4;   // 警察从道路出发搜索建筑的深度
    
    // ==================== 警察上报相关 ====================
    private Set<EntityID> reportedVictimBuildingsByPolice;
    
    // ==================== 状态 ====================
    private boolean initialized;
    private int lastLogTime;

    public MySearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                    ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);

        this.unsearchedBuildings = new HashSet<>();
        this.searchedBuildings = new HashSet<>();
        this.zoneBuildings = new HashSet<>();
        this.zoneUnsearched = new HashSet<>();
        this.reportedVictimBuildingsByPolice = new HashSet<>();
        this.agentType = ai.me().getStandardURN();
        this.result = null;
        this.initialized = false;
        this.zoneCompleted = false;
        this.zoneTotalCount = 0;
        this.lastLogTime = 0;

        StandardEntityURN agentURN = ai.me().getStandardURN();
        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                if (agentURN == AMBULANCE_TEAM) {
                    this.pathPlanning = moduleManager.getModule(
                        "MySearch.PathPlanning.Ambulance", 
                        "ZCWL_2026.module.algorithm.PathPlanning");
                    this.clustering = moduleManager.getModule(
                        "MySearch.Clustering.Ambulance", 
                        "ZCWL_2026.module.algorithm.SampleKMeans");
                } else if (agentURN == FIRE_BRIGADE) {
                    this.pathPlanning = moduleManager.getModule(
                        "MySearch.PathPlanning.Fire", 
                        "ZCWL_2026.module.algorithm.PathPlanning");
                    this.clustering = moduleManager.getModule(
                        "MySearch.Clustering.Fire", 
                        "ZCWL_2026.module.algorithm.SampleKMeans");
                } else if (agentURN == POLICE_FORCE) {
                    this.pathPlanning = moduleManager.getModule(
                        "MySearch.PathPlanning.Police", 
                        "ZCWL_2026.module.algorithm.PathPlanning");
                    // 警察使用专用的均衡聚类
                    this.policeClustering = moduleManager.getModule(
                        "MySearch.PoliceClustering", 
                        "ZCWL_2026.module.algorithm.PoliceBalancedClustering");
                }
                break;
        }

        registerModule(this.pathPlanning);
        if (this.clustering != null) {
            registerModule(this.clustering);
        }
        if (this.policeClustering != null) {
            registerModule(this.policeClustering);
        }
    }
    
    /**
     * 初始化本区域建筑集合
     * - 消防/救护：使用 SampleKMeans 聚类结果
     * - 警察：使用 PoliceBalancedClustering 获取本簇道路，BFS 搜索连通建筑
     */
    private void initZoneBuildings() {
        this.zoneBuildings.clear();
        this.zoneUnsearched.clear();
        
        // 警察分支
        if (this.agentType == POLICE_FORCE && this.policeClustering != null) {
            initPoliceZoneBuildings();
            return;
        }
        
        // 消防/救护分支 (原有逻辑)
        if (this.clustering != null) {
            try {
                this.clustering.calc();
                int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
                if (clusterIndex >= 0) {
                    Collection<EntityID> clusterIds = this.clustering.getClusterEntityIDs(clusterIndex);
                    if (clusterIds != null && !clusterIds.isEmpty()) {
                        for (EntityID id : clusterIds) {
                            StandardEntity entity = this.worldInfo.getEntity(id);
                            if (entity instanceof Building && entity.getStandardURN() != REFUGE) {
                                this.zoneBuildings.add(id);
                                this.zoneUnsearched.add(id);
                            }
                        }
                        this.zoneTotalCount = this.zoneBuildings.size();
                        return;
                    }
                }
            } catch (Exception e) {
                System.err.println("[MySearch] 聚类获取失败: " + e.getMessage());
            }
        }
        this.zoneTotalCount = 0;
        System.err.println("[MySearch] 无法获取本区域建筑，将直接使用全局探索");
    }
    
    /**
     * 警察专用：基于本簇道路 BFS 搜索连通建筑
     */
    private void initPoliceZoneBuildings() {
        try {
            this.policeClustering.calc();
            int clusterIndex = this.policeClustering.getClusterIndex(this.agentInfo.getID());
            if (clusterIndex < 0) {
                System.err.println("[MySearch] 警察 " + this.agentInfo.getID() + " 未分配到任何簇");
                this.zoneTotalCount = 0;
                return;
            }
            
            // 获取本簇内的所有道路
            Collection<EntityID> clusterRoads = this.policeClustering.getClusterEntityIDs(clusterIndex);
            if (clusterRoads == null || clusterRoads.isEmpty()) {
                System.err.println("[MySearch] 警察 " + this.agentInfo.getID() + " 的簇没有道路");
                this.zoneTotalCount = 0;
                return;
            }
            
            // 从这些道路出发 BFS 搜索建筑
            Set<EntityID> foundBuildings = bfsBuildingsFromRoads(clusterRoads, POLICE_BFS_DEPTH);
            
            for (EntityID buildingId : foundBuildings) {
                StandardEntity entity = this.worldInfo.getEntity(buildingId);
                if (entity instanceof Building && entity.getStandardURN() != REFUGE) {
                    this.zoneBuildings.add(buildingId);
                    this.zoneUnsearched.add(buildingId);
                }
            }
            
            this.zoneTotalCount = this.zoneBuildings.size();
            System.err.println("[MySearch] 警察 " + this.agentInfo.getID() + 
                               " 从 " + clusterRoads.size() + " 条道路出发，发现 " + 
                               this.zoneTotalCount + " 个连通建筑");
        } catch (Exception e) {
            System.err.println("[MySearch] 警察区域建筑初始化失败: " + e.getMessage());
            this.zoneTotalCount = 0;
        }
    }
    
    /**
     * 从给定的道路集合出发，BFS 搜索指定深度内的所有建筑
     * @param seedRoads 起始道路集合
     * @param maxDepth 最大搜索深度
     * @return 搜索到的建筑 ID 集合
     */
    private Set<EntityID> bfsBuildingsFromRoads(Collection<EntityID> seedRoads, int maxDepth) {
        Set<EntityID> buildings = new HashSet<>();
        Set<EntityID> visited = new HashSet<>();
        Queue<EntityID> queue = new LinkedList<>();
        
        // 初始化队列：所有种子道路
        for (EntityID roadId : seedRoads) {
            StandardEntity e = this.worldInfo.getEntity(roadId);
            if (e instanceof Road) {
                queue.add(roadId);
                visited.add(roadId);
            }
        }
        
        int depth = 0;
        while (!queue.isEmpty() && depth <= maxDepth) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                EntityID currentId = queue.poll();
                StandardEntity current = this.worldInfo.getEntity(currentId);
                if (current == null) continue;
                
                // 如果是建筑，加入结果集（但不继续从建筑向外扩展，保持搜索在道路网络上）
                if (current instanceof Building) {
                    buildings.add(currentId);
                    continue;
                }
                
                // 如果是道路，检查邻居
                if (current instanceof Road) {
                    Road road = (Road) current;
                    for (EntityID neighborId : road.getNeighbours()) {
                        if (visited.contains(neighborId)) continue;
                        visited.add(neighborId);
                        
                        StandardEntity neighbor = this.worldInfo.getEntity(neighborId);
                        if (neighbor instanceof Building) {
                            buildings.add(neighborId);
                            // 建筑不加入队列，但已访问标记防止重复
                        } else if (neighbor instanceof Road) {
                            queue.add(neighborId);
                        }
                    }
                }
            }
            depth++;
        }
        
        return buildings;
    }
    
    private void updateZoneProgress() {
        if (zoneCompleted) return;
        for (EntityID searched : searchedBuildings) {
            zoneUnsearched.remove(searched);
        }
        if (zoneUnsearched.isEmpty() && zoneTotalCount > 0 && !zoneCompleted) {
            zoneCompleted = true;
            System.err.println("╔══════════════════════════════════════════════════════════════╗");
            System.err.println("║  [MySearch] 🎉 本区域探索完成！共探索 " + zoneTotalCount + " 个建筑     ║");
            System.err.println("║  切换到全局探索模式                                          ║");
            System.err.println("╚══════════════════════════════════════════════════════════════╝");
        }
    }
    
    private void updateCurrentTargets() {
        if (!zoneCompleted && zoneTotalCount > 0) {
            this.unsearchedBuildings.clear();
            this.unsearchedBuildings.addAll(this.zoneUnsearched);
        } else if (zoneCompleted || zoneTotalCount == 0) {
            if (this.unsearchedBuildings.isEmpty()) {
                initFullMapBuildings();
                this.unsearchedBuildings.removeAll(this.searchedBuildings);
            }
        }
    }
    
    private void initFullMapBuildings() {
        this.unsearchedBuildings.clear();
        for (StandardEntity entity : this.worldInfo.getEntitiesOfType(
                BUILDING, GAS_STATION, AMBULANCE_CENTRE, FIRE_STATION, POLICE_OFFICE)) {
            if (entity.getStandardURN() != REFUGE) {
                this.unsearchedBuildings.add(entity.getID());
            }
        }
        System.err.println("[MySearch] 全图建筑总数: " + this.unsearchedBuildings.size());
    }
    
    private void initialize() {
        if (initialized) return;
        initZoneBuildings();
        initFullMapBuildings();
        updateCurrentTargets();
        initialized = true;
    }

    @Override
    public Search updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;
        
        int currentTime = this.agentInfo.getTime();
        
        if (!initialized) {
            initialize();
        }
        
        // ========== 警察视线侦察上报 ==========
        if (this.agentType == POLICE_FORCE) {
            // 1. 变化实体中的平民
            for (EntityID id : this.worldInfo.getChanged().getChangedEntities()) {
                StandardEntity e = this.worldInfo.getEntity(id);
                if (e instanceof Civilian) {
                    Civilian c = (Civilian) e;
                    if (c.isHPDefined() && c.getHP() > 0 
                        && c.isBuriednessDefined() && c.getBuriedness() > 0
                        && c.isPositionDefined()) {
                        EntityID buildingId = c.getPosition();
                        if (buildingId != null && !reportedVictimBuildingsByPolice.contains(buildingId)) {
                            MessageCivilian msgCivilian = new MessageCivilian(true, c);
                            messageManager.addMessage(msgCivilian);
                            reportedVictimBuildingsByPolice.add(buildingId);
                        }
                    }
                }
            }
            
            // 2. 检查自身所在建筑
            EntityID currentPos = this.agentInfo.getPosition();
            StandardEntity posEntity = this.worldInfo.getEntity(currentPos);
            if (posEntity instanceof Building) {
                Building building = (Building) posEntity;
                if (!reportedVictimBuildingsByPolice.contains(building.getID())) {
                    boolean found = false;
                    Collection<StandardEntity> entities = this.worldInfo.getObjectsInRange(building.getID(), 0);
                    for (StandardEntity se : entities) {
                        if (se instanceof Civilian) {
                            Civilian c = (Civilian) se;
                            if (c.isPositionDefined() && c.getPosition().equals(building.getID())) {
                                if (c.isHPDefined() && c.getHP() > 0 && c.isBuriednessDefined() && c.getBuriedness() > 0) {
                                    MessageCivilian msgCivilian = new MessageCivilian(true, c);
                                    messageManager.addMessage(msgCivilian);
                                    found = true;
                                }
                            }
                        }
                    }
                    if (found) {
                        reportedVictimBuildingsByPolice.add(building.getID());
                    }
                }
            }
        }
        // ====================================
        
        for (CommunicationMessage message : messageManager.getReceivedMessageList(MessageBuilding.class)) {
            MessageBuilding mb = (MessageBuilding) message;
            this.searchedBuildings.add(mb.getBuildingID());
        }
        
        for (EntityID changedId : this.worldInfo.getChanged().getChangedEntities()) {
            StandardEntity entity = this.worldInfo.getEntity(changedId);
            if (entity instanceof Building) {
                this.searchedBuildings.add(changedId);
            }
        }
        
        updateZoneProgress();
        updateCurrentTargets();
        this.unsearchedBuildings.removeAll(this.searchedBuildings);
        
        if (currentTime - lastLogTime > 50) {
            lastLogTime = currentTime;
            String agentName = (this.agentType == FIRE_BRIGADE) ? "消防车" :
                              (this.agentType == AMBULANCE_TEAM) ? "救护车" : "警车";
            if (!zoneCompleted && zoneTotalCount > 0) {
                int remaining = zoneUnsearched.size();
                int explored = zoneTotalCount - remaining;
                System.err.println("[MySearch] " + agentName + " 区域探索: " + 
                                   explored + "/" + zoneTotalCount + "，剩余=" + remaining);
            } else if (zoneCompleted) {
                System.err.println("[MySearch] " + agentName + " 全局探索，剩余建筑数: " + 
                                   this.unsearchedBuildings.size());
            }
        }
        return this;
    }

    @Override
    public Search calc() {
        this.result = null;
        if (!initialized) {
            initialize();
        }
        if (this.unsearchedBuildings.isEmpty()) {
            if (zoneCompleted || zoneTotalCount == 0) {
                initFullMapBuildings();
                this.unsearchedBuildings.removeAll(this.searchedBuildings);
            } else {
                this.unsearchedBuildings.addAll(this.zoneUnsearched);
            }
        }
        if (this.unsearchedBuildings.isEmpty()) {
            return this;
        }
        EntityID currentPos = this.agentInfo.getPosition();
        EntityID nearest = null;
        int minDistance = Integer.MAX_VALUE;
        for (EntityID buildingId : this.unsearchedBuildings) {
            if (this.searchedBuildings.contains(buildingId)) continue;
            List<EntityID> path = this.pathPlanning.getResult(currentPos, buildingId);
            if (path != null && !path.isEmpty() && path.size() < minDistance) {
                minDistance = path.size();
                nearest = buildingId;
            }
        }
        this.result = nearest;
        return this;
    }

    public int getZoneProgress() {
        if (zoneTotalCount == 0) return 100;
        int explored = zoneTotalCount - zoneUnsearched.size();
        return (explored * 100) / zoneTotalCount;
    }
    
    public boolean isZoneCompleted() {
        return zoneCompleted;
    }
    
    public String getCurrentMode() {
        return (zoneCompleted || zoneTotalCount == 0) ? "全局探索" : "区域探索";
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }

    @Override
    public Search precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) return this;
        if (this.clustering != null) this.clustering.precompute(precomputeData);
        if (this.policeClustering != null) this.policeClustering.precompute(precomputeData);
        if (this.pathPlanning != null) this.pathPlanning.precompute(precomputeData);
        return this;
    }

    @Override
    public Search resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        if (this.clustering != null) this.clustering.resume(precomputeData);
        if (this.policeClustering != null) this.policeClustering.resume(precomputeData);
        if (this.pathPlanning != null) this.pathPlanning.resume(precomputeData);
        this.worldInfo.requestRollback();
        return this;
    }

    @Override
    public Search preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) return this;
        if (this.clustering != null) this.clustering.preparate();
        if (this.policeClustering != null) this.policeClustering.preparate();
        if (this.pathPlanning != null) this.pathPlanning.preparate();
        this.worldInfo.requestRollback();
        return this;
    }
}