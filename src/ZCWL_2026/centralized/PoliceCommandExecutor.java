package ZCWL_2026.centralized;

import adf.core.component.communication.CommunicationMessage;
import adf.core.component.centralized.CommandExecutor;
import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class PoliceCommandExecutor extends adf.core.component.centralized.CommandExecutor<CommandPolice> {

    private static final int ACTION_UNKNOWN = -1;
    private static final int ACTION_REST = CommandPolice.ACTION_REST;
    private static final int ACTION_MOVE = CommandPolice.ACTION_MOVE;
    private static final int ACTION_CLEAR = CommandPolice.ACTION_CLEAR;
    private static final int ACTION_AUTONOMY = CommandPolice.ACTION_AUTONOMY;

    private int commandType;
    private EntityID target;
    private EntityID commanderID;

    private PathPlanning pathPlanning;
    private ExtAction actionExtClear;
    private ExtAction actionExtMove;

    private List<EntityID> myZoneRoads;
    private List<EntityID> allRoads;
    private Set<EntityID> trappedPathRoads;
    private Set<EntityID> completedTasks;
    private MessageManager msgManager;

    public PoliceCommandExecutor(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                  ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.commandType = ACTION_UNKNOWN;
        this.trappedPathRoads = new HashSet<>();
        this.completedTasks = new HashSet<>();
        this.msgManager = null;
        
       /* System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [ZCWL_2026] 警车执行器已加载（跨区域版）                     ║");
        System.err.println("║  ID: " + ai.getID() + "                                         ║");
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
       */                             
        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                    "PoliceCommandExecutor.PathPlanning",
                    "ZCWL_2026.module.algorithm.PathPlanning");
                this.actionExtClear = moduleManager.getExtAction(
                    "PoliceCommandExecutor.ActionExtClear",
                    "ZCWL_2026.extraction.ActionExtClear");
                this.actionExtMove = moduleManager.getExtAction(
                    "PoliceCommandExecutor.ActionExtMove",
                    "ZCWL_2026.extraction.ActionExtMove");
                break;
        }
        
        initZones();
    }

    private void initZones() {
        List<EntityID> allPolice = new ArrayList<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(POLICE_FORCE)) {
            allPolice.add(e.getID());
        }
        allPolice.sort(Comparator.comparingInt(EntityID::getValue));

        this.allRoads = new ArrayList<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(ROAD)) {
            this.allRoads.add(e.getID());
        }

        int myIndex = allPolice.indexOf(this.agentInfo.getID());
        if (myIndex < 0) return;

        int totalPolice = allPolice.size();
        if (totalPolice == 0) return;

        int roadsPerPolice = this.allRoads.size() / totalPolice;
        int start = myIndex * roadsPerPolice;
        int end = (myIndex == totalPolice - 1) ? this.allRoads.size() : (myIndex + 1) * roadsPerPolice;

        this.myZoneRoads = new ArrayList<>();
        for (int i = start; i < end && i < this.allRoads.size(); i++) {
            this.myZoneRoads.add(this.allRoads.get(i));
        }
        
        System.err.println("[ZCWL_2026] 警车 ID:" + this.agentInfo.getID() + 
                           " 负责区域道路数: " + this.myZoneRoads.size() + 
                           "，全局道路数: " + this.allRoads.size());
    }

    @Override
    public CommandExecutor<CommandPolice> setCommand(CommandPolice command) {
        System.err.println("[警车执行器] setCommand 被调用，命令类型=" + command.getAction() + 
                           ", toID=" + command.getToID() + ", target=" + command.getTargetID());
        EntityID agentID = this.agentInfo.getID();
        if (command.isToIDDefined() && Objects.requireNonNull(command.getToID()).getValue() == agentID.getValue()) {
            this.commandType = command.getAction();
            this.target = command.getTargetID();
            this.commanderID = command.getSenderID();
            
            System.err.println("╔══════════════════════════════════════════════════════════════╗");
            System.err.println("║  [ZCWL_2026] 🚓 警车 ID:" + agentID + " 收到命令！             ║");
            System.err.println("║  目标: " + this.target + "                                    ║");
            System.err.println("║  命令类型: " + this.commandType + " (" + 
                               (this.commandType == ACTION_AUTONOMY ? "自主" : 
                                this.commandType == ACTION_CLEAR ? "清理" :
                                this.commandType == ACTION_MOVE ? "移动" : "其他") + ")");
            System.err.println("╚══════════════════════════════════════════════════════════════╝");
        } else {
            System.err.println("[警车执行器] 命令被忽略，toID=" + command.getToID() + ", agentID=" + agentID);
        }
        return this;
    }

    @Override
    public CommandExecutor<CommandPolice> updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;

        this.msgManager = messageManager;

        this.pathPlanning.updateInfo(messageManager);
        this.actionExtClear.updateInfo(messageManager);
        this.actionExtMove.updateInfo(messageManager);

        updateTrappedPaths(messageManager);

        System.err.println("[警车执行器] updateInfo, 当前命令类型=" + this.commandType + 
                           ", target=" + this.target + ", 位置=" + this.agentInfo.getPosition());

        if (isCommandCompleted()) {
            System.err.println("[警车执行器] 命令已完成，准备发送报告");
            if (this.commandType != ACTION_UNKNOWN && this.target != null) {
                if (!completedTasks.contains(this.target)) {
                    completedTasks.add(this.target);
                    messageManager.addMessage(new MessageReport(true, true, false, this.commanderID));
                    System.err.println("[ZCWL_2026] ✅ 警车 ID:" + this.agentInfo.getID() + 
                                       " 完成任务: " + this.target);
                }
                this.commandType = ACTION_UNKNOWN;
                this.target = null;
                this.commanderID = null;
            }
        }
        return this;
    }

    private void updateTrappedPaths(MessageManager messageManager) {
        for (CommunicationMessage message : messageManager.getReceivedMessageList(CommandPolice.class)) {
            CommandPolice cmd = (CommandPolice) message;
            if (cmd.isBroadcast() && cmd.getTargetID() != null) {
                trappedPathRoads.add(cmd.getTargetID());
            }
        }
    }

    @Override
    public CommandExecutor<CommandPolice> precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) return this;
        this.pathPlanning.precompute(precomputeData);
        this.actionExtClear.precompute(precomputeData);
        this.actionExtMove.precompute(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor<CommandPolice> resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        this.pathPlanning.resume(precomputeData);
        this.actionExtClear.resume(precomputeData);
        this.actionExtMove.resume(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor<CommandPolice> preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) return this;
        this.pathPlanning.preparate();
        this.actionExtClear.preparate();
        this.actionExtMove.preparate();
        return this;
    }

    @Override
    public CommandExecutor<CommandPolice> calc() {
        System.err.println("[警车执行器] calc() 被调用，命令类型=" + this.commandType + ", target=" + this.target);
        this.result = null;

        switch (this.commandType) {
            case ACTION_REST:
                //System.err.println("[警车执行器] ACTION_REST -> 转为自主模式");
                this.commandType = ACTION_AUTONOMY;
                this.result = handleAutonomy();
                return this;

            case ACTION_MOVE:
                if (this.target != null) {
                    System.err.println("[警车执行器] ACTION_MOVE -> 移动到目标: " + this.target);
                    this.result = this.actionExtMove.setTarget(this.target).calc().getAction();
                }
                return this;

            case ACTION_CLEAR:
                if (this.target != null) {
                    System.err.println("[警车执行器] ACTION_CLEAR -> 清理目标: " + this.target);
                    this.result = this.actionExtClear.setTarget(this.target).calc().getAction();
                }
                return this;

            case ACTION_AUTONOMY:
                //System.err.println("[警车执行器] ACTION_AUTONOMY -> 自主模式");
                this.result = handleAutonomy();
                return this;

            default:
                System.err.println("[警车执行器] 未知命令类型: " + this.commandType);
                return this;
        }
    }

    /**
 * 自主模式 - 优先本区域，然后被困通道，最后全局
 */
private Action handleAutonomy() {
    EntityID position = this.agentInfo.getPosition();
    System.err.println("[警车执行器] handleAutonomy() 开始，当前位置=" + position + ", target=" + this.target);

    // 1. 检查当前任务是否有效
    if (this.target != null) {
        StandardEntity targetEntity = this.worldInfo.getEntity(this.target);
        if (targetEntity instanceof Road) {
            Road road = (Road) targetEntity;
            if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                System.err.println("[警车执行器] 当前目标道路 " + this.target + " 仍有路障，继续清理");
                return this.actionExtClear.setTarget(this.target).calc().getAction();
            } else {
                System.err.println("[警车执行器] 当前目标道路 " + this.target + " 已清理，清除目标");
                this.target = null;
            }
        } else {
            System.err.println("[警车执行器] 当前目标不是道路，清除目标");
            this.target = null;
        }
    }

    // 2. 本区域内最近的障碍物（新增）
    EntityID zoneRoad = findNearestRoadInZone(position);
    if (zoneRoad != null) {
        this.target = zoneRoad;
        System.err.println("[警车执行器] 在本区域内找到障碍物: " + zoneRoad + "，准备清理");
        return this.actionExtClear.setTarget(this.target).calc().getAction();
    }

    // 3. 优先处理被困人员通道任务
    EntityID bestRoad = findBestTrappedPath(position);
    if (bestRoad != null) {
        this.target = bestRoad;
       // System.err.println("[警车执行器] 找到被困人员通道: " + bestRoad + "，准备清理");
        return this.actionExtClear.setTarget(this.target).calc().getAction();
    }

    // 4. 跨区域寻找最近的障碍物（全局搜索）
    bestRoad = findNearestRoadToClearGlobal(position);
    if (bestRoad != null) {
        this.target = bestRoad;
        //System.err.println("[警车执行器] 找到全局最近障碍物: " + bestRoad + "，准备清理");
        return this.actionExtClear.setTarget(this.target).calc().getAction();
    }

    // 5. 真的没有任何障碍物，才去避难所
    System.err.println("[警车执行器] 全图没有需要清理的道路，去避难所休息");
    return handleRest();
}

    /**
     * 寻找被困人员通道
     */
    private EntityID findBestTrappedPath(EntityID position) {
        if (this.trappedPathRoads.isEmpty()) return null;
        System.err.println("[警车执行器] 检查被困人员通道，数量=" + this.trappedPathRoads.size());
        
        EntityID nearest = null;
        int minDistance = Integer.MAX_VALUE;
        List<EntityID> toRemove = new ArrayList<>();
        
        for (EntityID roadId : this.trappedPathRoads) {
            StandardEntity entity = this.worldInfo.getEntity(roadId);
            if (entity instanceof Road) {
                Road road = (Road) entity;
                if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
                    toRemove.add(roadId);
                    continue;
                }
            } else {
                toRemove.add(roadId);
                continue;
            }
            
            if (isReachable(position, roadId)) {
                List<EntityID> path = this.pathPlanning.getResult(position, roadId);
                if (path != null && path.size() < minDistance) {
                    minDistance = path.size();
                    nearest = roadId;
                }
            }
        }
        
        this.trappedPathRoads.removeAll(toRemove);
        return nearest;
    }

    /**
     * 跨区域寻找最近的障碍物（全局搜索，由近及远）
     */
    private EntityID findNearestRoadToClearGlobal(EntityID position) {
        if (this.allRoads == null) return null;

        EntityID nearest = null;
        int minDistance = Integer.MAX_VALUE;
        int totalCandidates = 0;
        
        for (EntityID roadId : this.allRoads) {
            StandardEntity entity = this.worldInfo.getEntity(roadId);
            if (entity instanceof Road) {
                Road road = (Road) entity;
                if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                    totalCandidates++;
                    if (isReachable(position, roadId)) {
                        List<EntityID> path = this.pathPlanning.getResult(position, roadId);
                        if (path != null && path.size() < minDistance) {
                            minDistance = path.size();
                            nearest = roadId;
                        }
                    }
                }
            }
        }
        
        if (totalCandidates == 0) {
            System.err.println("[ZCWL_2026] 警车 ID:" + this.agentInfo.getID() + " 全图没有需要清理的道路");
        } else if (nearest == null) {
            System.err.println("[ZCWL_2026] 警车 ID:" + this.agentInfo.getID() + " 全图有 " + totalCandidates + " 条道路需要清理，但都不可达");
        } else {
            System.err.println("[ZCWL_2026] 警车 ID:" + this.agentInfo.getID() + " 找到全图最近需要清理的道路: " + nearest + ", 距离: " + minDistance);
        }
        
        return nearest;
    }

    /**
     * 处理休息动作 - 去避难所
     */
    private Action handleRest() {
        EntityID position = this.agentInfo.getPosition();
        Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
        
        if (refuges.contains(position)) {
            System.err.println("[ZCWL_2026] 警车 ID:" + this.agentInfo.getID() + " 已在避难所");
            return new ActionRest();
        } else {
            this.pathPlanning.setFrom(position);
            this.pathPlanning.setDestination(refuges);
            List<EntityID> path = this.pathPlanning.calc().getResult();
            if (path != null && path.size() > 0) {
                System.err.println("[ZCWL_2026] 警车 ID:" + this.agentInfo.getID() + " 去避难所");
                return new ActionMove(path);
            }
            System.err.println("[ZCWL_2026] 警车 ID:" + this.agentInfo.getID() + " 无法找到避难所路径，原地休息");
            return new ActionRest();
        }
    }

    /**
     * 检查是否可达
     */
    private boolean isReachable(EntityID from, EntityID to) {
        List<EntityID> path = this.pathPlanning.getResult(from, to);
        return path != null && path.size() > 0;
    }

    /**
     * 计算距离
     */
    private double getDistance(EntityID from, EntityID to) {
        if (this.pathPlanning != null) {
            return this.pathPlanning.getDistance(from, to);
        }
        StandardEntity fromEntity = this.worldInfo.getEntity(from);
        StandardEntity toEntity = this.worldInfo.getEntity(to);
        if (fromEntity != null && toEntity != null) {
            return this.worldInfo.getDistance(fromEntity, toEntity);
        }
        return Double.MAX_VALUE;
    }

    /**
     * 判断命令是否完成
     */
    private boolean isCommandCompleted() {
        switch (this.commandType) {
            case ACTION_REST:
                return true;

            case ACTION_MOVE:
                return this.target == null || 
                       this.agentInfo.getPosition().getValue() == this.target.getValue();

            case ACTION_CLEAR:
                if (this.target == null) return true;
                StandardEntity entity = this.worldInfo.getEntity(this.target);
                if (entity instanceof Road) {
                    Road road = (Road) entity;
                    return !road.isBlockadesDefined() || road.getBlockades().isEmpty();
                }
                return true;

            case ACTION_AUTONOMY:
                if (this.target != null) {
                    StandardEntity targetEntity = this.worldInfo.getEntity(this.target);
                    if (targetEntity instanceof Road) {
                        Road road = (Road) targetEntity;
                        return !road.isBlockadesDefined() || road.getBlockades().isEmpty();
                    }
                    return true;
                }
                return false;

            default:
                return true;
        }
    }

    public List<EntityID> getResult() {
        return null;
    }
    /**
 * 在本区域内寻找最近的、有路障且可达的道路
 */
private EntityID findNearestRoadInZone(EntityID position) {
    if (this.myZoneRoads == null || this.myZoneRoads.isEmpty()) {
        return null;
    }

    EntityID nearest = null;
    int minDistance = Integer.MAX_VALUE;

    for (EntityID roadId : this.myZoneRoads) {
        StandardEntity entity = this.worldInfo.getEntity(roadId);
        if (!(entity instanceof Road)) continue;
        Road road = (Road) entity;
        if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) continue;

        // 检查是否可达
        if (!isReachable(position, roadId)) continue;

        List<EntityID> path = this.pathPlanning.getResult(position, roadId);
        if (path != null && path.size() < minDistance) {
            minDistance = path.size();
            nearest = roadId;
        }
    }

    if (nearest != null) {
        System.err.println("[警车执行器] 本区域内找到最近道路: " + nearest + "，距离=" + minDistance);
    }
    return nearest;
}
}