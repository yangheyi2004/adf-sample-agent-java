package ZCWL_2026.centralized;

import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.standard.bundle.centralized.CommandScout;
import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.centralized.CommandExecutor;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.AbstractEntity;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import java.util.stream.Collectors;

import static rescuecore2.standard.entities.StandardEntityURN.*;

/**
 * 警察侦察命令执行器
 * 功能：清理道路障碍 + 探索建筑发现被困人员
 */
public class ScoutPoliceCommandExecutor extends adf.core.component.centralized.CommandExecutor {

    private static final int ACTION_UNKNOWN = -1;
    private static final int ACTION_SCOUT = 1;
    private static final int ACTION_EXPLORE_BUILDING = 2;  // 探索建筑模式

    private PathPlanning pathPlanning;
    private ExtAction actionExtClear;

    private int commandType;
    private Collection<EntityID> scoutTargets;
    private EntityID commanderID;
    
    // 救援通道信息
    private Set<EntityID> rescueRoutes;
    private Map<EntityID, Integer> rescueRoutePriority;
    
    // 建筑探索相关
    private Set<EntityID> exploredBuildings;        // 已探索的建筑
    private Set<EntityID> buildingsWithVictims;     // 有被困人员的建筑
    private Queue<EntityID> unexploredBuildings;    // 未探索的建筑队列
    private int buildingSearchRadius;
    
    // 侦查相关
    private Set<EntityID> discoveredVictims;        // 发现的被困人员
    private int scoutRange;

    public ScoutPoliceCommandExecutor(AgentInfo ai, WorldInfo wi, ScenarioInfo si, 
                                      ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.commandType = ACTION_UNKNOWN;
        this.scoutTargets = new HashSet<>();
        this.commanderID = null;
        this.rescueRoutes = new HashSet<>();
        this.rescueRoutePriority = new HashMap<>();
        
        // 建筑探索记忆
        this.exploredBuildings = new HashSet<>();
        this.buildingsWithVictims = new HashSet<>();
        this.unexploredBuildings = new LinkedList<>();
        this.discoveredVictims = new HashSet<>();
        
        // 读取配置
        this.buildingSearchRadius = developData.getInteger("ScoutPoliceCommandExecutor.buildingSearchRadius", 3000);
        this.scoutRange = developData.getInteger("ScoutPoliceCommandExecutor.scoutRange", 5000);

        String pathPlanningClass = "ZCWL_2026.module.algorithm.PathPlanning";
        String actionExtClearClass = "ZCWL_2026.extraction.ActionExtClear";
        
        try {
            switch (scenarioInfo.getMode()) {
                case PRECOMPUTATION_PHASE:
                case PRECOMPUTED:
                case NON_PRECOMPUTE:
                default:
                    this.pathPlanning = moduleManager.getModule("ScoutPoliceCommandExecutor.PathPlanning", pathPlanningClass);
                    this.actionExtClear = moduleManager.getExtAction("ScoutPoliceCommandExecutor.ActionExtClear", actionExtClearClass);
                    break;
            }
        } catch (Exception e) {
            System.err.println("ScoutPoliceCommandExecutor: Error loading modules - " + e.getMessage());
        }
        
        // 初始化建筑探索队列
        this.initBuildingExploration();
    }

    @Override
    public CommandExecutor setCommand(CommunicationMessage command) {
        if (command instanceof CommandScout) {
            CommandScout cmd = (CommandScout) command;
            EntityID agentID = this.agentInfo.getID();
            if (cmd.isToIDDefined() && (Objects.requireNonNull(cmd.getToID()).getValue() == agentID.getValue())) {
                EntityID target = cmd.getTargetID();
                if (target == null) {
                    target = this.agentInfo.getPosition();
                }
                this.commandType = ACTION_SCOUT;
                this.commanderID = cmd.getSenderID();
                this.scoutTargets = new HashSet<>();
                this.scoutTargets.addAll(
                        worldInfo.getObjectsInRange(target, cmd.getRange())
                                .stream()
                                .filter(e -> e instanceof Area && e.getStandardURN() != REFUGE)
                                .map(AbstractEntity::getID)
                                .collect(Collectors.toList())
                );
            }
        }
        return this;
    }
    
    /**
     * 设置救援通道信息
     */
    public void setRescueRoutes(Set<EntityID> routes, Map<EntityID, Integer> priorities) {
        this.rescueRoutes.clear();
        this.rescueRoutes.addAll(routes);
        this.rescueRoutePriority.clear();
        this.rescueRoutePriority.putAll(priorities);
    }
    
    // ==================== 建筑探索方法 ====================
    
    private void initBuildingExploration() {
        try {
            Collection<StandardEntity> buildings = this.worldInfo.getEntitiesOfType(BUILDING, GAS_STATION);
            
            for (StandardEntity entity : buildings) {
                if (entity instanceof Building) {
                    Building building = (Building) entity;
                    if (this.hasVictimInBuilding(building)) {
                        this.buildingsWithVictims.add(building.getID());
                        this.addVictimsFromBuilding(building);
                    } else {
                        this.unexploredBuildings.add(building.getID());
                    }
                }
            }
            
            this.sortUnexploredBuildingsByDistance();
            
        } catch (Exception e) {
            System.err.println("ScoutPoliceCommandExecutor: Error init building exploration - " + e.getMessage());
        }
    }
    
    private boolean hasVictimInBuilding(Building building) {
        Collection<StandardEntity> entities = this.worldInfo.getObjectsInRange(building.getID(), 1000);
        for (StandardEntity e : entities) {
            if (e instanceof Civilian) {
                Civilian c = (Civilian) e;
                if (c.isPositionDefined() && c.getPosition().equals(building.getID())) {
                    if (c.isBuriednessDefined() && c.getBuriedness() > 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    private void addVictimsFromBuilding(Building building) {
        Collection<StandardEntity> entities = this.worldInfo.getObjectsInRange(building.getID(), 1000);
        for (StandardEntity e : entities) {
            if (e instanceof Civilian) {
                Civilian c = (Civilian) e;
                if (c.isPositionDefined() && c.getPosition().equals(building.getID())) {
                    if (c.isBuriednessDefined() && c.getBuriedness() > 0) {
                        this.discoveredVictims.add(c.getID());
                    }
                }
            }
        }
    }
    
    private void sortUnexploredBuildingsByDistance() {
        EntityID currentPos = this.agentInfo.getPosition();
        List<EntityID> sortedList = new ArrayList<>(this.unexploredBuildings);
        
        sortedList.sort((a, b) -> {
            int distA = this.worldInfo.getDistance(currentPos, a);
            int distB = this.worldInfo.getDistance(currentPos, b);
            return Integer.compare(distA, distB);
        });
        
        this.unexploredBuildings.clear();
        this.unexploredBuildings.addAll(sortedList);
    }
    
    /**
     * 检查当前位置的建筑并探索
     */
    private void checkAndExploreCurrentBuilding(MessageManager messageManager) {
        EntityID position = this.agentInfo.getPosition();
        StandardEntity entity = this.worldInfo.getEntity(position);
        
        if (entity instanceof Building) {
            Building building = (Building) entity;
            EntityID buildingId = building.getID();
            
            // 如果建筑未探索
            if (!this.exploredBuildings.contains(buildingId) && 
                !this.buildingsWithVictims.contains(buildingId)) {
                
                if (this.hasVictimInBuilding(building)) {
                    this.buildingsWithVictims.add(buildingId);
                    this.addVictimsFromBuilding(building);
                    
                    // 上报发现的被困人员给消防员
                    for (EntityID victimId : this.discoveredVictims) {
                        StandardEntity victimEntity = this.worldInfo.getEntity(victimId);
                        if (victimEntity instanceof Civilian) {
                            MessageCivilian msg = new MessageCivilian(true, (Civilian) victimEntity);
                            messageManager.addMessage(msg);
                            System.out.println("Police discovered victim in building " + buildingId + 
                                " and reported to firefighters");
                        }
                    }
                } else {
                    this.exploredBuildings.add(buildingId);
                    this.unexploredBuildings.remove(buildingId);
                    System.out.println("Police explored building: " + buildingId.getValue() + " - no victims");
                }
            }
        }
    }
    
    /**
     * 侦查周围区域，发现被困人员
     */
    private void scoutForVictims(MessageManager messageManager) {
        EntityID position = this.agentInfo.getPosition();
        Collection<StandardEntity> entitiesInRange = this.worldInfo.getObjectsInRange(position, this.scoutRange);
        
        for (StandardEntity entity : entitiesInRange) {
            if (entity instanceof Civilian) {
                Civilian civilian = (Civilian) entity;
                if (civilian.isBuriednessDefined() && civilian.getBuriedness() > 0) {
                    if (!this.discoveredVictims.contains(civilian.getID())) {
                        this.discoveredVictims.add(civilian.getID());
                        
                        // 立即上报给消防员
                        MessageCivilian msg = new MessageCivilian(true, civilian);
                        messageManager.addMessage(msg);
                        System.out.println("Police scouted and reported victim: " + civilian.getID().getValue());
                    }
                }
            }
        }
    }
    
    private boolean isRescueRoute(EntityID targetId) {
        return this.rescueRoutes.contains(targetId);
    }
    
    private int getRescueRoutePriority(EntityID targetId) {
        return this.rescueRoutePriority.getOrDefault(targetId, 0);
    }
    
    private EntityID selectOptimalTarget(EntityID currentPosition) {
        if (this.scoutTargets == null || this.scoutTargets.isEmpty()) {
            return null;
        }
        
        List<EntityID> rescueTargets = new ArrayList<>();
        List<EntityID> normalTargets = new ArrayList<>();
        
        for (EntityID target : this.scoutTargets) {
            if (this.isRescueRoute(target)) {
                rescueTargets.add(target);
            } else {
                normalTargets.add(target);
            }
        }
        
        if (!rescueTargets.isEmpty()) {
            rescueTargets.sort((a, b) -> {
                int priorityA = this.getRescueRoutePriority(a);
                int priorityB = this.getRescueRoutePriority(b);
                if (priorityA != priorityB) return priorityB - priorityA;
                
                int distA = this.worldInfo.getDistance(currentPosition, a);
                int distB = this.worldInfo.getDistance(currentPosition, b);
                return Integer.compare(distA, distB);
            });
            return rescueTargets.get(0);
        }
        
        if (!normalTargets.isEmpty()) {
            normalTargets.sort((a, b) -> {
                int distA = this.worldInfo.getDistance(currentPosition, a);
                int distB = this.worldInfo.getDistance(currentPosition, b);
                return Integer.compare(distA, distB);
            });
            return normalTargets.get(0);
        }
        
        return null;
    }
    
    private boolean isTargetCompleted(EntityID target) {
        StandardEntity entity = this.worldInfo.getEntity(target);
        if (entity instanceof Road) {
            Road road = (Road) entity;
            if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
                this.scoutTargets.remove(target);
                return true;
            }
        }
        return false;
    }
    
    private void cleanCompletedTargets() {
        List<EntityID> completed = new ArrayList<>();
        for (EntityID target : this.scoutTargets) {
            if (this.isTargetCompleted(target)) {
                completed.add(target);
            }
        }
        this.scoutTargets.removeAll(completed);
    }

    @Override
    public CommandExecutor precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) return this;
        if (this.pathPlanning != null) this.pathPlanning.precompute(precomputeData);
        if (this.actionExtClear != null) this.actionExtClear.precompute(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        if (this.pathPlanning != null) this.pathPlanning.resume(precomputeData);
        if (this.actionExtClear != null) this.actionExtClear.resume(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) return this;
        if (this.pathPlanning != null) this.pathPlanning.preparate();
        if (this.actionExtClear != null) this.actionExtClear.preparate();
        return this;
    }

    @Override
    public CommandExecutor updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;
        
        if (this.pathPlanning != null) this.pathPlanning.updateInfo(messageManager);
        if (this.actionExtClear != null) this.actionExtClear.updateInfo(messageManager);
        
        // 探索当前位置的建筑
        this.checkAndExploreCurrentBuilding(messageManager);
        
        // 侦查周围区域发现被困人员
        this.scoutForVictims(messageManager);

        if (this.isCommandCompleted()) {
            if (this.commandType != ACTION_UNKNOWN) {
                messageManager.addMessage(new MessageReport(true, true, false, this.commanderID));
                this.commandType = ACTION_UNKNOWN;
                this.scoutTargets = new HashSet<>();
                this.commanderID = null;
            }
        }
        return this;
    }

    @Override
    public CommandExecutor calc() {
        this.result = null;
        EntityID position = this.agentInfo.getPosition();
        
        if (this.commandType == ACTION_SCOUT) {
            if (this.scoutTargets == null || this.scoutTargets.isEmpty()) {
                // 没有侦察任务时，可以探索建筑
                return this;
            }
            
            this.cleanCompletedTargets();
            
            if (this.scoutTargets.isEmpty()) {
                return this;
            }
            
            EntityID selectedTarget = this.selectOptimalTarget(position);
            if (selectedTarget == null) {
                return this;
            }
            
            if (this.pathPlanning == null) return this;
            
            this.pathPlanning.setFrom(position);
            this.pathPlanning.setDestination(selectedTarget);
            List<EntityID> path = this.pathPlanning.calc().getResult();
            
            if (path != null && !path.isEmpty()) {
                EntityID target = path.get(path.size() - 1);
                Action action = null;
                
                if (this.actionExtClear != null) {
                    action = this.actionExtClear.setTarget(target).calc().getAction();
                }
                
                if (action == null) {
                    action = new ActionMove(path);
                }
                this.result = action;
            }
        }
        return this;
    }

    private boolean isCommandCompleted() {
        if (this.commandType == ACTION_SCOUT) {
            if (this.scoutTargets != null && !this.scoutTargets.isEmpty()) {
                this.scoutTargets.removeAll(this.worldInfo.getChanged().getChangedEntities());
            }
            return (this.scoutTargets == null || this.scoutTargets.isEmpty());
        }
        return true;
    }
}