package ZCWL_2026.extraction;

import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.police.ActionClear;
import adf.core.agent.communication.MessageManager;
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

import static rescuecore2.standard.entities.StandardEntityURN.BUILDING;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

public class PoliceExtAction extends ExtAction {

    private PathPlanning pathPlanning;
    private EntityID target;

    public PoliceExtAction(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                           ModuleManager moduleManager, DevelopData developData) {
        super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        this.target = null;

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                    "PoliceExtAction.PathPlanning",
                    "ZCWL_2026.module.algorithm.PathPlanning");
                break;
        }
    }

    @Override
    public ExtAction setTarget(EntityID target) {
        this.target = target;
        return this;
    }

    @Override
    public ExtAction calc() {
        this.result = null;
        if (this.target == null) return this;

        StandardEntity targetEntity = this.worldInfo.getEntity(this.target);
        if (targetEntity == null) return this;

        // 处理道路目标
        if (targetEntity instanceof Road) {
            Road road = (Road) targetEntity;

            // 检查道路是否仍然需要清理
            boolean needClear = !road.isBlockadesDefined() || !road.getBlockades().isEmpty();

            if (!needClear) {
                // ★ 道路已畅通：不要再清理，改为寻找该道路相邻的最近未搜索建筑
                EntityID nextBuilding = findNearestAdjacentBuilding(road.getID());
                if (nextBuilding != null) {
                    // 移动到建筑进行探索
                    List<EntityID> path = this.pathPlanning.getResult(
                            this.agentInfo.getPosition(), nextBuilding);
                    if (path != null && !path.isEmpty()) {
                        this.result = new ActionMove(path);
                        return this;
                    }
                }
                // 如果没有相邻建筑，或者找不到路径，就什么也不做（避免卡死）
                return this;
            }

            // 道路仍需清理：按路径逐步推进
            List<EntityID> path = this.pathPlanning.getResult(
                    this.agentInfo.getPosition(), road.getID());
            if (path != null && !path.isEmpty()) {
                for (EntityID stepId : path) {
                    StandardEntity stepEntity = this.worldInfo.getEntity(stepId);
                    if (stepEntity instanceof Road) {
                        Road stepRoad = (Road) stepEntity;
                        if (!stepRoad.isBlockadesDefined()) {
                            // 未定义 → 坐标清理
                            this.result = new ActionClear(stepRoad.getX(), stepRoad.getY());
                            return this;
                        }
                        if (!stepRoad.getBlockades().isEmpty()) {
                            // 有障碍物 → 清理第一个障碍物
                            this.result = new ActionClear(stepRoad.getBlockades().get(0));
                            return this;
                        }
                    }
                }
                // 路径上所有道路都畅通，直接移动到目标道路
                this.result = new ActionMove(path);
                return this;
            }
            return this;
        }

        // 非道路目标：普通移动
        List<EntityID> path = this.pathPlanning.getResult(
                this.agentInfo.getPosition(), this.target);
        if (path != null && !path.isEmpty()) {
            this.result = new ActionMove(path);
        }
        return this;
    }

    /**
     * 寻找与给定道路ID相邻的最近一个未搜索建筑（非REFUGE）。
     */
    private EntityID findNearestAdjacentBuilding(EntityID roadId) {
        StandardEntity roadEntity = this.worldInfo.getEntity(roadId);
        if (!(roadEntity instanceof Road)) return null;
        Road road = (Road) roadEntity;

        EntityID nearestBuilding = null;
        double minDist = Double.MAX_VALUE;

        for (EntityID neighborId : road.getNeighbours()) {
            StandardEntity neighbor = this.worldInfo.getEntity(neighborId);
            if (neighbor instanceof Building && neighbor.getStandardURN() != REFUGE) {
                // 可选：检查是否已经搜索过，但这里简易处理，直接移动到建筑，由MySearch更新信息
                double dist = this.worldInfo.getDistance(this.agentInfo.getPosition(), neighborId);
                if (dist < minDist) {
                    minDist = dist;
                    nearestBuilding = neighborId;
                }
            }
        }
        return nearestBuilding;
    }

    @Override
    public ExtAction precompute(PrecomputeData pd) { return this; }
    @Override
    public ExtAction resume(PrecomputeData pd) { return this; }
    @Override
    public ExtAction preparate() { return this; }
    @Override
    public ExtAction updateInfo(MessageManager mm) { return this; }
}