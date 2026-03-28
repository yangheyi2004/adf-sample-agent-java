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

public class MySearch extends Search {
    private PathPlanning pathPlanning;
    private Clustering clustering;
    private EntityID result;
    private Collection<EntityID> unsearchedBuildingIDs;
    private Set<EntityID> searchedBuildings;
    private StandardEntityURN agentType;
    
    // 救护车专用
    private Set<EntityID> checkedBuildingsForVictims;
    private EntityID pendingVictim;      // 待处理的受伤平民
    private Set<EntityID> discoveredWaitingVictims;  // 已发现的等待装载平民（避免重复日志）

    public MySearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                    ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);

        this.unsearchedBuildingIDs = new HashSet<>();
        this.searchedBuildings = new HashSet<>();
        this.checkedBuildingsForVictims = new HashSet<>();
        this.discoveredWaitingVictims = new HashSet<>();
        this.agentType = ai.me().getStandardURN();
        this.pendingVictim = null;

        StandardEntityURN agentURN = ai.me().getStandardURN();
        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                if (agentURN == AMBULANCE_TEAM) {
                    this.pathPlanning = moduleManager.getModule("MySearch.PathPlanning.Ambulance", "ZCWL_2026.module.algorithm.PathPlanning");
                    this.clustering = moduleManager.getModule("MySearch.Clustering.Ambulance", "ZCWL_2026.module.algorithm.SampleKMeans");
                } else if (agentURN == FIRE_BRIGADE) {
                    this.pathPlanning = moduleManager.getModule("MySearch.PathPlanning.Fire", "ZCWL_2026.module.algorithm.PathPlanning");
                    this.clustering = moduleManager.getModule("MySearch.Clustering.Fire", "ZCWL_2026.module.algorithm.SampleKMeans");
                } else if (agentURN == POLICE_FORCE) {
                    this.pathPlanning = moduleManager.getModule("MySearch.PathPlanning.Police", "ZCWL_2026.module.algorithm.PathPlanning");
                    this.clustering = moduleManager.getModule("MySearch.Clustering.Police", "ZCWL_2026.module.algorithm.SampleKMeans");
                }
                break;
        }

        registerModule(this.pathPlanning);
        registerModule(this.clustering);
        
        String agentName = "";
        if (agentURN == FIRE_BRIGADE) agentName = "消防车";
        else if (agentURN == AMBULANCE_TEAM) agentName = "救护车";
        else if (agentURN == POLICE_FORCE) agentName = "警车";
        
        System.err.println("[ZCWL_2026] " + agentName + " ID:" + ai.getID() + " 搜索模块已加载");
    }

    @Override
    public Search updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;

        this.unsearchedBuildingIDs.removeAll(this.searchedBuildings);
        this.unsearchedBuildingIDs.removeAll(this.worldInfo.getChanged().getChangedEntities());

        for (CommunicationMessage message : messageManager.getReceivedMessageList(MessageBuilding.class)) {
            MessageBuilding mb = (MessageBuilding) message;
            this.searchedBuildings.add(mb.getBuildingID());
            this.unsearchedBuildingIDs.remove(mb.getBuildingID());
        }

        if (this.unsearchedBuildingIDs.isEmpty()) {
            this.reset();
            this.unsearchedBuildingIDs.removeAll(this.searchedBuildings);
        }
        
        // ========== 救护车专用：接收紧急装载请求 ==========
        if (this.agentType == AMBULANCE_TEAM) {
            for (CommunicationMessage message : messageManager.getReceivedMessageList(MessageCivilian.class)) {
                MessageCivilian mc = (MessageCivilian) message;
                // 受伤且未被掩埋的平民（紧急装载请求）
                if (mc.isDamageDefined() && mc.getDamage() > 0 && 
                    (!mc.isBuriednessDefined() || mc.getBuriedness() == 0)) {
                    this.pendingVictim = mc.getAgentID();
                    System.err.println("╔══════════════════════════════════════════════════════════════╗");
                    System.err.println("║  [救护车搜索] 🚨 收到紧急装载请求！                           ║");
                    System.err.println("║  平民: " + this.pendingVictim + " 需要立即装载                  ║");
                    System.err.println("╚══════════════════════════════════════════════════════════════╝");
                }
            }
        }
        
        return this;
    }

    /**
     * 方案2：检查当前位置附近是否有等待装载的平民（救护车专用）
     * 包括：在道路上、未被掩埋、受伤的平民
     */
    private EntityID checkNearbyWaitingVictim(EntityID position) {
        if (this.agentType != AMBULANCE_TEAM) return null;
        
        // 获取当前位置附近1000范围内的所有实体
        Collection<StandardEntity> entitiesInRange = this.worldInfo.getObjectsInRange(position, 1000);
        
        EntityID nearest = null;
        double minDist = Double.MAX_VALUE;
        
        for (StandardEntity e : entitiesInRange) {
            if (e instanceof Civilian) {
                Civilian civilian = (Civilian) e;
                // 平民在道路上、未被掩埋、受伤 > 0
                if (civilian.isPositionDefined() && civilian.isDamageDefined() && civilian.getDamage() > 0 &&
                    (!civilian.isBuriednessDefined() || civilian.getBuriedness() == 0)) {
                    
                    // 检查平民是否在道路上
                    StandardEntity posEntity = this.worldInfo.getEntity(civilian.getPosition());
                    if (posEntity instanceof Road) {
                        double dist = this.worldInfo.getDistance(position, civilian.getPosition());
                        if (dist < minDist) {
                            minDist = dist;
                            nearest = civilian.getID();
                        }
                        
                        // 打印日志，方便调试
                        if (!discoveredWaitingVictims.contains(civilian.getID())) {
                            System.err.println("╔══════════════════════════════════════════════════════════════╗");
                            System.err.println("║  [救护车搜索-方案2] 🚑 发现道路上等待装载的平民！            ║");
                            System.err.println("║  平民 ID: " + civilian.getID() + " 伤害=" + civilian.getDamage());
                            System.err.println("║  位置: " + civilian.getPosition() + " (道路)");
                            System.err.println("╚══════════════════════════════════════════════════════════════╝");
                            discoveredWaitingVictims.add(civilian.getID());
                        }
                    }
                }
            }
        }
        return nearest;
    }

    @Override
    public Search calc() {
        this.result = null;
        
        // ========== 方案2：救护车优先处理道路上等待装载的平民 ==========
        if (this.agentType == AMBULANCE_TEAM) {
            // 1. 先检查当前道路上是否有等待装载的平民
            EntityID waitingVictim = checkNearbyWaitingVictim(this.agentInfo.getPosition());
            if (waitingVictim != null) {
                this.pendingVictim = waitingVictim;
                this.result = waitingVictim;  // 直接返回平民ID
                System.err.println("[救护车搜索-方案2] 🚑 发现道路上等待装载的平民，直接返回ID: " + waitingVictim);
                return this;
            }
            
            // 2. 检查已有的紧急任务（来自消息）
            if (this.pendingVictim != null) {
                Human victim = (Human) this.worldInfo.getEntity(this.pendingVictim);
                if (victim != null && victim.isHPDefined() && victim.getHP() > 0 &&
                    victim.isDamageDefined() && victim.getDamage() > 0 &&
                    (!victim.isBuriednessDefined() || victim.getBuriedness() == 0)) {
                    
                    if (victim.isPositionDefined()) {
                        // 返回平民位置，让执行器去移动
                        this.result = victim.getPosition();
                        System.err.println("[救护车搜索-方案2] 🚑 紧急装载任务优先，前往位置: " + this.result + " (平民ID: " + this.pendingVictim + ")");
                        return this;
                    }
                }
                this.pendingVictim = null;
            }
        }
        
        // 如果有当前目标建筑且可达，直接返回
        if (this.result != null && isReachable(this.result)) {
            return this;
        }
        
        // 找到最近的未探索建筑
        this.result = findNearestReachableBuilding();
        
        // ========== 救护车专用：在找到建筑后，检查该建筑内是否有受伤平民 ==========
        if (this.agentType == AMBULANCE_TEAM && this.result != null) {
            EntityID victimInBuilding = checkBuildingForInjured(this.result);
            if (victimInBuilding != null) {
                this.pendingVictim = victimInBuilding;
                this.result = null;
                System.err.println("[救护车搜索-方案2] 🚑 在建筑内发现受伤平民: " + victimInBuilding);
                return this;
            }
        }
        
        if (this.result != null) {
            String agentName = "";
            if (agentType == FIRE_BRIGADE) agentName = "消防车";
            else if (agentType == AMBULANCE_TEAM) agentName = "救护车";
            else if (agentType == POLICE_FORCE) agentName = "警车";
            System.err.println("[ZCWL_2026] " + agentName + " ID:" + this.agentInfo.getID() + 
                               " 搜索到未探索建筑: " + this.result);
        }
        
        return this;
    }

    /**
     * 检查建筑内是否有受伤平民（救护车专用）
     */
    private EntityID checkBuildingForInjured(EntityID buildingId) {
        if (this.agentType != AMBULANCE_TEAM) {
            return null;
        }
        
        if (checkedBuildingsForVictims.contains(buildingId)) {
            return null;
        }
        
        StandardEntity entity = this.worldInfo.getEntity(buildingId);
        if (!(entity instanceof Building)) {
            return null;
        }
        
        Collection<StandardEntity> entitiesInRange = this.worldInfo.getObjectsInRange(buildingId, 1000);
        
        for (StandardEntity e : entitiesInRange) {
            if (e instanceof Civilian) {
                Civilian civilian = (Civilian) e;
                if (civilian.isPositionDefined() && civilian.getPosition().equals(buildingId)) {
                    // 受伤但未被掩埋的平民
                    if (civilian.isDamageDefined() && civilian.getDamage() > 0 &&
                        (!civilian.isBuriednessDefined() || civilian.getBuriedness() == 0)) {
                        
                        System.err.println("╔══════════════════════════════════════════════════════════════╗");
                        System.err.println("║  [救护车搜索] 🚑 在建筑 " + buildingId + " 内发现受伤平民！    ║");
                        System.err.println("║  平民 ID: " + civilian.getID() + " 伤害=" + civilian.getDamage() + "      ║");
                        System.err.println("╚══════════════════════════════════════════════════════════════╝");
                        
                        checkedBuildingsForVictims.add(buildingId);
                        return civilian.getID();
                    }
                }
            }
        }
        
        checkedBuildingsForVictims.add(buildingId);
        return null;
    }

    @Override
    public EntityID getTarget() {
        // 救护车：优先返回受伤平民
        if (this.agentType == AMBULANCE_TEAM && this.pendingVictim != null) {
            return this.pendingVictim;
        }
        return this.result;
    }

    private EntityID findNearestReachableBuilding() {
        EntityID currentPos = this.agentInfo.getPosition();
        EntityID nearest = null;
        int minDistance = Integer.MAX_VALUE;
        
        for (EntityID buildingId : this.unsearchedBuildingIDs) {
            if (isReachable(buildingId)) {
                List<EntityID> path = this.pathPlanning.getResult(currentPos, buildingId);
                if (path != null && path.size() < minDistance) {
                    minDistance = path.size();
                    nearest = buildingId;
                }
            }
        }
        
        return nearest;
    }

    private boolean isReachable(EntityID target) {
        EntityID currentPos = this.agentInfo.getPosition();
        List<EntityID> path = this.pathPlanning.getResult(currentPos, target);
        return path != null && path.size() > 0;
    }

    private void reset() {
        this.unsearchedBuildingIDs.clear();

        Collection<StandardEntity> clusterEntities = null;
        if (this.clustering != null) {
            int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
            clusterEntities = this.clustering.getClusterEntities(clusterIndex);
        }
        
        if (clusterEntities != null && clusterEntities.size() > 0) {
            for (StandardEntity entity : clusterEntities) {
                if (entity instanceof Building && entity.getStandardURN() != REFUGE) {
                    if (!this.searchedBuildings.contains(entity.getID())) {
                        this.unsearchedBuildingIDs.add(entity.getID());
                    }
                }
            }
        } else {
            for (StandardEntity entity : this.worldInfo.getEntitiesOfType(BUILDING, GAS_STATION, AMBULANCE_CENTRE, FIRE_STATION, POLICE_OFFICE)) {
                if (!this.searchedBuildings.contains(entity.getID())) {
                    this.unsearchedBuildingIDs.add(entity.getID());
                }
            }
        }
        
        if (this.agentType == AMBULANCE_TEAM) {
            this.checkedBuildingsForVictims.clear();
            this.discoveredWaitingVictims.clear();
        }
        
        String agentName = "";
        if (agentType == FIRE_BRIGADE) agentName = "消防车";
        else if (agentType == AMBULANCE_TEAM) agentName = "救护车";
        else if (agentType == POLICE_FORCE) agentName = "警车";
        System.err.println("[ZCWL_2026] " + agentName + " ID:" + this.agentInfo.getID() + 
                           " 重置搜索列表，剩余未探索建筑: " + this.unsearchedBuildingIDs.size());
    }

    @Override
    public Search precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) return this;
        return this;
    }

    @Override
    public Search resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        this.worldInfo.requestRollback();
        return this;
    }

    @Override
    public Search preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) return this;
        this.worldInfo.requestRollback();
        return this;
    }
}