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
    private StandardEntityURN agentType;
    
    private EntityID result;
    private Set<EntityID> unsearchedBuildings;
    private Set<EntityID> searchedBuildings;
    
    private Set<EntityID> zoneBuildings;
    private Set<EntityID> zoneUnsearched;
    private Collection<EntityID> myClusterBuildings;
    private boolean zoneCompleted;
    private int zoneTotalCount;
    
    private Set<EntityID> reportedVictimBuildingsByPolice;
    
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
                    this.clustering = moduleManager.getModule(
                        "MySearch.Clustering.Police", 
                        "ZCWL_2026.module.algorithm.SampleKMeans");
                }
                break;
        }

        registerModule(this.pathPlanning);
        registerModule(this.clustering);
    }
    
    private void initZoneBuildings() {
        this.zoneBuildings.clear();
        this.zoneUnsearched.clear();
        
        if (this.clustering != null) {
            try {
                this.clustering.calc();
                int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
                if (clusterIndex >= 0) {
                    Collection<EntityID> clusterIds = this.clustering.getClusterEntityIDs(clusterIndex);
                    if (clusterIds != null && !clusterIds.isEmpty()) {
                        this.myClusterBuildings = new ArrayList<>(clusterIds);
                        for (EntityID id : this.myClusterBuildings) {
                            StandardEntity entity = this.worldInfo.getEntity(id);
                            if (entity instanceof Building) {
                                this.zoneBuildings.add(id);
                                this.zoneUnsearched.add(id);
                            }
                        }
                        this.zoneTotalCount = this.zoneBuildings.size();
                        return;
                    }
                }
            } catch (Exception e) {
                //System.err.println("[MySearch] 聚类获取失败: " + e.getMessage());
            }
        }
        this.zoneTotalCount = 0;
        //System.err.println("[MySearch] 无法获取本区域建筑，将直接使用全局探索");
    }
    
    private void updateZoneProgress() {
        if (zoneCompleted) return;
        int beforeCount = zoneUnsearched.size();
        for (EntityID searched : searchedBuildings) {
            zoneUnsearched.remove(searched);
        }
        if (zoneUnsearched.isEmpty() && zoneTotalCount > 0 && !zoneCompleted) {
            zoneCompleted = true;
            //System.err.println("╔══════════════════════════════════════════════════════════════╗");
            //System.err.println("║  [MySearch] 本区域探索完成！共探索 " + zoneTotalCount + " 个建筑       ║");
            //System.err.println("║  切换到全局探索模式                                          ║");
            //System.err.println("╚══════════════════════════════════════════════════════════════╝");
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
            this.unsearchedBuildings.add(entity.getID());
        }
        //System.err.println("[MySearch] 全图建筑总数: " + this.unsearchedBuildings.size());
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
        
        // 警察视线侦察上报
        if (this.agentType == POLICE_FORCE) {
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
            
            EntityID currentPos = this.agentInfo.getPosition();
            StandardEntity posEntity = this.worldInfo.getEntity(currentPos);
            if (posEntity instanceof Building) {
                Building building = (Building) posEntity;
                if (!reportedVictimBuildingsByPolice.contains(building.getID())) {
                    Collection<StandardEntity> entities = this.worldInfo.getObjectsInRange(building.getID(), 0);
                    for (StandardEntity se : entities) {
                        if (se instanceof Civilian) {
                            Civilian c = (Civilian) se;
                            if (c.isPositionDefined() && c.getPosition().equals(building.getID())) {
                                if (c.isHPDefined() && c.getHP() > 0 && c.isBuriednessDefined() && c.getBuriedness() > 0) {
                                    MessageCivilian msgCivilian = new MessageCivilian(true, c);
                                    messageManager.addMessage(msgCivilian);
                                    reportedVictimBuildingsByPolice.add(building.getID());
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        
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
                              (this.agentType == AMBULANCE_TEAM) ? "救护车" : "警察";
            if (!zoneCompleted && zoneTotalCount > 0) {
                int remaining = zoneUnsearched.size();
                int explored = zoneTotalCount - remaining;
                //System.err.println("[MySearch] " + agentName + " 区域探索: " + 
                                   //explored + "/" + zoneTotalCount + "，剩余=" + remaining);
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
