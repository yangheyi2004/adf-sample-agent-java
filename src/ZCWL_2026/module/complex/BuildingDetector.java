package ZCWL_2026.module.complex;

import adf.core.component.module.algorithm.Clustering;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageBuilding;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;

public class BuildingDetector extends adf.core.component.module.complex.BuildingDetector {

    private EntityID result;
    private Clustering clustering;
    private Set<EntityID> checkedBuildings;
    private MessageManager messageManager;
    
    // ========== 超时放弃相关 ==========
    private Map<EntityID, Integer> targetStartTime;      // 开始灭火时间
    private Map<EntityID, Integer> targetLastProgress;   // 最后进展时间（火势减弱）
    private Map<EntityID, Integer> targetLastFieryness;  // 上次火势等级
    private Map<EntityID, Integer> targetCooldown;       // 放弃后冷却步数
    private static final int TARGET_TIMEOUT = 60;         // 60步无进展放弃
    private static final int COOLDOWN_STEPS = 20;

    // 火情缓存（用于重复报告）
    private Set<EntityID> reportedFireBuildings;
    private int lastFireCheckTime;
    private static final int FIRE_REPORT_INTERVAL = 5;

    public BuildingDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                            ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.checkedBuildings = new HashSet<>();
        this.reportedFireBuildings = new HashSet<>();
        this.lastFireCheckTime = 0;
        
        // 初始化超时相关
        this.targetStartTime = new HashMap<>();
        this.targetLastProgress = new HashMap<>();
        this.targetLastFieryness = new HashMap<>();
        this.targetCooldown = new HashMap<>();
        
        System.err.println("[BuildingDetector] 消防车 ID:" + ai.getID() + " 建筑检测器已加载");
        
        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.clustering = moduleManager.getModule(
                    "BuildingDetector.Clustering",
                    "ZCWL_2026.module.algorithm.SampleKMeans");
                break;
        }
        registerModule(this.clustering);
    }

    @Override
    public BuildingDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;
        this.messageManager = messageManager;
        int currentTime = this.agentInfo.getTime();
        
        // 递减冷却
        targetCooldown.replaceAll((k, v) -> v - 1);
        targetCooldown.values().removeIf(v -> v <= 0);
        
        // 检查当前灭火目标的进展
        if (this.result != null) {
            Building b = (Building) this.worldInfo.getEntity(this.result);
            if (b != null && b.isFierynessDefined()) {
                int oldFiery = targetLastFieryness.getOrDefault(this.result, -1);
                int newFiery = b.getFieryness();
                if (oldFiery > newFiery && newFiery >= 0) {
                    // 火势减弱，有进展
                    targetLastProgress.put(this.result, currentTime);
                    targetLastFieryness.put(this.result, newFiery);
                } else if (oldFiery == -1) {
                    targetLastFieryness.put(this.result, newFiery);
                }
            }
        }
        
        // 清理超时目标
        List<EntityID> timeoutBuildings = new ArrayList<>();
        for (EntityID bid : reportedFireBuildings) {
            if (targetCooldown.containsKey(bid)) continue;
            Integer lastProgress = targetLastProgress.get(bid);
            if (lastProgress == null) continue;
            if (currentTime - lastProgress > TARGET_TIMEOUT) {
                timeoutBuildings.add(bid);
                System.err.printf("[BuildingDetector] 消防车 %d 放弃灭火目标 %d: 超过 %d 步无进展%n",
                    this.agentInfo.getID().getValue(), bid.getValue(), TARGET_TIMEOUT);
                targetCooldown.put(bid, COOLDOWN_STEPS);
            }
        }
        for (EntityID bid : timeoutBuildings) {
            reportedFireBuildings.remove(bid);
            targetStartTime.remove(bid);
            targetLastProgress.remove(bid);
            targetLastFieryness.remove(bid);
            if (this.result != null && this.result.equals(bid)) {
                this.result = null;
            }
        }
        
        return this;
    }

    @Override
    public BuildingDetector calc() {
        // 优先查找着火建筑，跳过冷却中的建筑
        this.result = findFireBuildingFirst();
        
        if (this.result == null) {
            this.result = this.calcTargetInCluster();
        }
        if (this.result == null) {
            this.result = this.calcTargetInWorld();
        }
        
        if (this.result != null) {
            this.checkedBuildings.add(this.result);
            Building building = (Building) this.worldInfo.getEntity(this.result);
            if (building != null && this.messageManager != null) {
                MessageBuilding msg = new MessageBuilding(true, building);
                this.messageManager.addMessage(msg);
                
                if (building.isOnFire()) {
                    if (!reportedFireBuildings.contains(this.result)) {
                        // 新目标，记录开始时间
                        int now = this.agentInfo.getTime();
                        targetStartTime.put(this.result, now);
                        targetLastProgress.put(this.result, now);
                        targetLastFieryness.put(this.result, building.getFieryness());
                        reportedFireBuildings.add(this.result);
                        System.err.println("[BuildingDetector] 消防车 ID:" + this.agentInfo.getID() + 
                                           " 🔥 发现并开始灭火: " + this.result);
                    }
                }
            }
        }
        
        // 定期重新检查火情（已有逻辑）
        int currentTime = this.agentInfo.getTime();
        if (currentTime - lastFireCheckTime > FIRE_REPORT_INTERVAL) {
            lastFireCheckTime = currentTime;
            recheckFireBuildings();
        }
        
        return this;
    }
    
    private EntityID findFireBuildingFirst() {
        // 优先在自己簇内找着火建筑，排除冷却中的
        if (this.clustering != null) {
            int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
            Collection<StandardEntity> elements = this.clustering.getClusterEntities(clusterIndex);
            if (elements != null && !elements.isEmpty()) {
                for (StandardEntity entity : elements) {
                    if (entity instanceof Building) {
                        Building b = (Building) entity;
                        if (b.isOnFire() && !this.checkedBuildings.contains(entity.getID()) 
                            && !targetCooldown.containsKey(entity.getID())) {
                            return entity.getID();
                        }
                    }
                }
            }
        }
        // 全局查找，排除冷却中的
        for (StandardEntity entity : this.worldInfo.getEntitiesOfType(BUILDING, GAS_STATION)) {
            Building b = (Building) entity;
            if (b.isOnFire() && !this.checkedBuildings.contains(entity.getID())
                && !targetCooldown.containsKey(entity.getID())) {
                return entity.getID();
            }
        }
        return null;
    }
    
    private void recheckFireBuildings() {
        Set<EntityID> toRemove = new HashSet<>();
        for (EntityID buildingId : reportedFireBuildings) {
            Building b = (Building) this.worldInfo.getEntity(buildingId);
            if (b == null || !b.isOnFire()) {
                toRemove.add(buildingId);
                // 清理超时记录
                targetStartTime.remove(buildingId);
                targetLastProgress.remove(buildingId);
                targetLastFieryness.remove(buildingId);
            } else if (this.messageManager != null) {
                // 火势仍在，继续报告
                MessageBuilding msg = new MessageBuilding(true, b);
                this.messageManager.addMessage(msg);
            }
        }
        reportedFireBuildings.removeAll(toRemove);
    }

    private EntityID calcTargetInCluster() {
        // 原实现（略）
        return null;
    }

    private EntityID calcTargetInWorld() {
        // 原实现（略）
        return null;
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }

    @Override
    public BuildingDetector precompute(PrecomputeData precomputeData) { return this; }
    @Override
    public BuildingDetector resume(PrecomputeData precomputeData) { return this; }
    @Override
    public BuildingDetector preparate() { return this; }
}