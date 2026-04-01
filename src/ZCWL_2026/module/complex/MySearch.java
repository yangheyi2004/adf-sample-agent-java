package ZCWL_2026.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageBuilding;
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
 * 1. 优先探索本区域内的建筑
 * 2. 本区域探索完成后，切换到全局探索
 */
public class MySearch extends Search {
    
    // ==================== 核心组件 ====================
    private PathPlanning pathPlanning;
    private Clustering clustering;
    private StandardEntityURN agentType;
    
    // ==================== 建筑搜索相关 ====================
    private EntityID result;
    private Set<EntityID> unsearchedBuildings;      // 当前要探索的建筑（可能是区域或全局）
    private Set<EntityID> searchedBuildings;        // 已探索的建筑（全局记录）
    
    // ==================== 区域探索相关 ====================
    private Set<EntityID> zoneBuildings;            // 本区域的所有建筑
    private Set<EntityID> zoneUnsearched;           // 本区域内未探索的建筑
    private Collection<EntityID> myClusterBuildings; // 聚类返回的实体ID（用于调试）
    private boolean zoneCompleted;                  // 本区域是否已探索完成
    private int zoneTotalCount;                     // 本区域建筑总数
    
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
        this.myClusterBuildings = new ArrayList<>();
        this.agentType = ai.me().getStandardURN();
        this.result = null;
        this.initialized = false;
        this.zoneCompleted = false;
        this.zoneTotalCount = 0;
        this.lastLogTime = 0;

        // 根据智能体类型加载模块
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
                    this.clustering = moduleManager.getModule(
                        "MySearch.Clustering.Police", 
                        "ZCWL_2026.module.algorithm.SampleKMeans");
                }
                break;
        }

        registerModule(this.pathPlanning);
        registerModule(this.clustering);
        
        String agentName = "";
        if (agentURN == FIRE_BRIGADE) agentName = "消防车";
        else if (agentURN == AMBULANCE_TEAM) agentName = "救护车";
        else if (agentURN == POLICE_FORCE) agentName = "警车";
        
        System.err.println("[MySearch] " + agentName + " ID:" + ai.getID() + " 建筑搜索模块已加载（区域优先策略）");
    }
    
    /**
     * 初始化区域建筑列表
     */
    private void initZoneBuildings() {
        this.zoneBuildings.clear();
        this.zoneUnsearched.clear();
        
        if (this.clustering != null) {
            try {
                // 先确保聚类已经计算
                this.clustering.calc();
                
                int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
                if (clusterIndex >= 0) {
                    // 使用 getClusterEntityIDs 获取簇内的实体ID列表
                    Collection<EntityID> clusterIds = this.clustering.getClusterEntityIDs(clusterIndex);
                    if (clusterIds != null && !clusterIds.isEmpty()) {
                        this.myClusterBuildings = new ArrayList<>(clusterIds);
                        for (EntityID id : this.myClusterBuildings) {
                            StandardEntity entity = this.worldInfo.getEntity(id);
                            // 只添加建筑类型，且不是避难所
                            if (entity instanceof Building && entity.getStandardURN() != REFUGE) {
                                this.zoneBuildings.add(id);
                                this.zoneUnsearched.add(id);
                            }
                        }
                        this.zoneTotalCount = this.zoneBuildings.size();
                        System.err.println("[MySearch] 聚类返回实体数: " + this.myClusterBuildings.size() +
                                           ", 本区域建筑数: " + this.zoneTotalCount);
                        return;
                    }
                }
            } catch (Exception e) {
                System.err.println("[MySearch] 聚类获取失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // 如果聚类失败，本区域为空
        this.zoneTotalCount = 0;
        System.err.println("[MySearch] 无法获取本区域建筑，将直接使用全局探索");
    }
    
    /**
     * 更新区域探索状态（从已探索建筑中移除）
     */
    private void updateZoneProgress() {
        if (zoneCompleted) return;
        
        int beforeCount = zoneUnsearched.size();
        
        // 从本区域未探索中移除已探索的建筑
        for (EntityID searched : searchedBuildings) {
            zoneUnsearched.remove(searched);
        }
        
        int afterCount = zoneUnsearched.size();
        if (beforeCount != afterCount && zoneTotalCount > 0) {
            int remaining = zoneUnsearched.size();
            int explored = zoneTotalCount - remaining;
            System.err.println("[MySearch] 区域探索进度: " + explored + "/" + zoneTotalCount);
        }
        
        // 检查是否完成
        if (zoneUnsearched.isEmpty() && zoneTotalCount > 0 && !zoneCompleted) {
            zoneCompleted = true;
            System.err.println("╔══════════════════════════════════════════════════════════════╗");
            System.err.println("║  [MySearch] 🎉 本区域探索完成！共探索 " + zoneTotalCount + " 个建筑     ║");
            System.err.println("║  切换到全局探索模式                                          ║");
            System.err.println("╚══════════════════════════════════════════════════════════════╝");
        }
    }
    
    /**
     * 更新当前要探索的建筑列表（根据策略）
     */
    private void updateCurrentTargets() {
        if (!zoneCompleted && zoneTotalCount > 0) {
            // 区域模式：探索本区域内未探索的建筑
            this.unsearchedBuildings.clear();
            this.unsearchedBuildings.addAll(this.zoneUnsearched);
        } else if (zoneCompleted || zoneTotalCount == 0) {
            // 全局模式：探索所有未探索的建筑
            if (this.unsearchedBuildings.isEmpty()) {
                initFullMapBuildings();
                // 移除已探索的
                this.unsearchedBuildings.removeAll(this.searchedBuildings);
            }
        }
    }
    
    /**
     * 初始化全图建筑列表
     */
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
    
    /**
     * 初始化（在第一次使用时调用）
     */
    private void initialize() {
        if (initialized) return;
        
        // 初始化区域建筑
        initZoneBuildings();
        
        // 初始化全图建筑（备用）
        initFullMapBuildings();
        
        // 根据策略设置当前目标
        updateCurrentTargets();
        
        initialized = true;
        System.err.println("[MySearch] 初始化完成，当前模式: " + 
                           (zoneTotalCount > 0 ? "区域优先（" + zoneTotalCount + "个建筑）" : "全局探索"));
    }

    @Override
    public Search updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;
        
        int currentTime = this.agentInfo.getTime();
        
        // 如果还没初始化，先初始化
        if (!initialized) {
            initialize();
        }
        
        // 从消息中同步已探索建筑
        for (CommunicationMessage message : messageManager.getReceivedMessageList(MessageBuilding.class)) {
            MessageBuilding mb = (MessageBuilding) message;
            this.searchedBuildings.add(mb.getBuildingID());
        }
        
        // 从世界变化中同步已探索建筑
        for (EntityID changedId : this.worldInfo.getChanged().getChangedEntities()) {
            StandardEntity entity = this.worldInfo.getEntity(changedId);
            if (entity instanceof Building) {
                this.searchedBuildings.add(changedId);
            }
        }
        
        // 更新区域探索进度
        updateZoneProgress();
        
        // 更新当前目标列表
        updateCurrentTargets();
        
        // 从当前目标中移除已探索的
        this.unsearchedBuildings.removeAll(this.searchedBuildings);
        
        // 定期输出日志（每50步）
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
        
        // 如果还没初始化，先初始化
        if (!initialized) {
            initialize();
        }
        
        // 如果没有未探索建筑，尝试重新初始化
        if (this.unsearchedBuildings.isEmpty()) {
            // 如果区域模式已完成，重新从全图获取
            if (zoneCompleted || zoneTotalCount == 0) {
                initFullMapBuildings();
                this.unsearchedBuildings.removeAll(this.searchedBuildings);
            } else {
                // 区域模式还有未探索建筑但列表为空，重新从区域获取
                this.unsearchedBuildings.addAll(this.zoneUnsearched);
            }
        }
        
        if (this.unsearchedBuildings.isEmpty()) {
            return this;
        }
        
        // 寻找最近的可达建筑
        EntityID currentPos = this.agentInfo.getPosition();
        EntityID nearest = null;
        int minDistance = Integer.MAX_VALUE;
        
        for (EntityID buildingId : this.unsearchedBuildings) {
            // 跳过已探索的
            if (this.searchedBuildings.contains(buildingId)) continue;
            
            // 检查是否可达
            List<EntityID> path = this.pathPlanning.getResult(currentPos, buildingId);
            if (path != null && !path.isEmpty() && path.size() < minDistance) {
                minDistance = path.size();
                nearest = buildingId;
            }
        }
        
        this.result = nearest;
        return this;
    }

    /**
     * 获取本区域探索进度（用于外部查询）
     */
    public int getZoneProgress() {
        if (zoneTotalCount == 0) return 100;
        int explored = zoneTotalCount - zoneUnsearched.size();
        return (explored * 100) / zoneTotalCount;
    }
    
    /**
     * 是否已完成本区域探索
     */
    public boolean isZoneCompleted() {
        return zoneCompleted;
    }
    
    /**
     * 获取当前模式名称
     */
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
        if (this.pathPlanning != null) this.pathPlanning.precompute(precomputeData);
        return this;
    }

    @Override
    public Search resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        if (this.clustering != null) this.clustering.resume(precomputeData);
        if (this.pathPlanning != null) this.pathPlanning.resume(precomputeData);
        this.worldInfo.requestRollback();
        return this;
    }

    @Override
    public Search preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) return this;
        if (this.clustering != null) this.clustering.preparate();
        if (this.pathPlanning != null) this.pathPlanning.preparate();
        this.worldInfo.requestRollback();
        return this;
    }
}