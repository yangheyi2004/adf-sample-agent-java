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
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class BuildingDetector extends adf.core.component.module.complex.BuildingDetector {

    private EntityID result;
    private Clustering clustering;
    private Set<EntityID> checkedBuildings;
    private MessageManager messageManager;

    public BuildingDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                            ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.checkedBuildings = new HashSet<>();
        
        System.err.println("[ZCWL_2026] 消防车 ID:" + ai.getID() + " 建筑检测器已加载");
        
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
        return this;
    }

    @Override
    public BuildingDetector calc() {
        this.result = this.calcTargetInCluster();
        if (this.result == null) {
            this.result = this.calcTargetInWorld();
        }
        
        // 标记建筑为已检查
        if (this.result != null) {
            this.checkedBuildings.add(this.result);
            
            // 发送建筑信息消息
            Building building = (Building) this.worldInfo.getEntity(this.result);
            if (building != null && this.messageManager != null) {
                MessageBuilding msg = new MessageBuilding(true, building);
                this.messageManager.addMessage(msg);
                
                if (building.isOnFire()) {
                    System.err.println("[ZCWL_2026] 消防车 ID:" + this.agentInfo.getID() + 
                                       " 发现着火建筑: " + this.result + 
                                       ", 火势: " + building.getFieryness());
                } else {
                    System.err.println("[ZCWL_2026] 消防车 ID:" + this.agentInfo.getID() + 
                                       " 检查建筑: " + this.result);
                }
            }
        }
        
        return this;
    }

    private EntityID calcTargetInCluster() {
        if (this.clustering == null) return null;
        
        int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
        Collection<StandardEntity> elements = this.clustering.getClusterEntities(clusterIndex);
        if (elements == null || elements.isEmpty()) return null;
        
        StandardEntity me = this.agentInfo.me();
        List<StandardEntity> agents = new ArrayList<>(this.worldInfo.getEntitiesOfType(FIRE_BRIGADE));
        Set<StandardEntity> fireBuildings = new HashSet<>();
        
        for (StandardEntity entity : elements) {
            if (entity instanceof Building && ((Building) entity).isOnFire()) {
                if (!this.checkedBuildings.contains(entity.getID())) {
                    fireBuildings.add(entity);
                }
            }
        }
        
        for (StandardEntity entity : fireBuildings) {
            if (agents.isEmpty()) break;
            else if (agents.size() == 1) {
                if (agents.get(0).getID().getValue() == me.getID().getValue()) {
                    return entity.getID();
                }
                break;
            }
            agents.sort(new DistanceSorter(this.worldInfo, entity));
            StandardEntity a0 = agents.get(0);
            StandardEntity a1 = agents.get(1);

            if (me.getID().getValue() == a0.getID().getValue() || me.getID().getValue() == a1.getID().getValue()) {
                return entity.getID();
            } else {
                agents.remove(a0);
                agents.remove(a1);
            }
        }
        return null;
    }

    private EntityID calcTargetInWorld() {
        Collection<StandardEntity> entities = this.worldInfo.getEntitiesOfType(
            BUILDING, GAS_STATION, AMBULANCE_CENTRE, FIRE_STATION, POLICE_OFFICE);
        
        StandardEntity me = this.agentInfo.me();
        List<StandardEntity> agents = new ArrayList<>(worldInfo.getEntitiesOfType(FIRE_BRIGADE));
        Set<StandardEntity> fireBuildings = new HashSet<>();
        
        for (StandardEntity entity : entities) {
            Building building = (Building) entity;
            if (building.isOnFire()) {
                if (!this.checkedBuildings.contains(entity.getID())) {
                    fireBuildings.add(entity);
                }
            }
        }
        
        for (StandardEntity entity : fireBuildings) {
            if (agents.isEmpty()) break;
            else if (agents.size() == 1) {
                if (agents.get(0).getID().getValue() == me.getID().getValue()) {
                    return entity.getID();
                }
                break;
            }
            agents.sort(new DistanceSorter(this.worldInfo, entity));
            StandardEntity a0 = agents.get(0);
            StandardEntity a1 = agents.get(1);

            if (me.getID().getValue() == a0.getID().getValue() || me.getID().getValue() == a1.getID().getValue()) {
                return entity.getID();
            } else {
                agents.remove(a0);
                agents.remove(a1);
            }
        }
        return null;
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }

    @Override
    public BuildingDetector precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) return this;
        return this;
    }

    @Override
    public BuildingDetector resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        return this;
    }

    @Override
    public BuildingDetector preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) return this;
        return this;
    }

    private class DistanceSorter implements Comparator<StandardEntity> {
        private StandardEntity reference;
        private WorldInfo worldInfo;

        DistanceSorter(WorldInfo wi, StandardEntity reference) {
            this.reference = reference;
            this.worldInfo = wi;
        }

        public int compare(StandardEntity a, StandardEntity b) {
            int d1 = this.worldInfo.getDistance(this.reference, a);
            int d2 = this.worldInfo.getDistance(this.reference, b);
            return d1 - d2;
        }
    }
}