package ZCWL_2026.extraction;

import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
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
import com.google.common.collect.Lists;
import java.util.*;
import java.util.stream.Collectors;
import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.misc.geometry.Vector2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class ActionExtClear extends adf.core.component.extaction.ExtAction {

    private PathPlanning pathPlanning;
    private int clearDistance;
    private int forcedMove;
    private int thresholdRest;
    private int kernelTime;
    private EntityID target;
    private Map<EntityID, Set<Point2D>> movePointCache;
    private int oldClearX;
    private int oldClearY;
    private int count;

    // ========== 无效清理检测相关字段 ==========
    private EntityID lastClearTarget = null;
    private int lastBlockadeCount = -1;
    private int invalidClearCounter = 0;
    private static final int INVALID_CLEAR_LIMIT = 5;

    public ActionExtClear(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                          ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.clearDistance = si.getClearRepairDistance();
        this.forcedMove = developData.getInteger("ActionExtClear.forcedMove", 3);
        this.thresholdRest = developData.getInteger("ActionExtClear.rest", 100);
        this.target = null;
        this.movePointCache = new HashMap<>();
        this.oldClearX = 0;
        this.oldClearY = 0;
        this.count = 0;

        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                    "ActionExtClear.PathPlanning",
                    "ZCWL_2026.module.algorithm.PathPlanning");
                break;
        }
    }

    @Override
    public ExtAction precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) return this;
        this.pathPlanning.precompute(precomputeData);
        try {
            this.kernelTime = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            this.kernelTime = -1;
        }
        return this;
    }

    @Override
    public ExtAction resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        this.pathPlanning.resume(precomputeData);
        try {
            this.kernelTime = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            this.kernelTime = -1;
        }
        return this;
    }

    @Override
    public ExtAction preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) return this;
        this.pathPlanning.preparate();
        try {
            this.kernelTime = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            this.kernelTime = -1;
        }
        return this;
    }

    @Override
    public ExtAction updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;
        this.pathPlanning.updateInfo(messageManager);
        return this;
    }

    @Override
    public ExtAction setTarget(EntityID target) {
        this.target = null;
        StandardEntity entity = this.worldInfo.getEntity(target);
        if (entity != null) {
            if (entity instanceof Road) {
                this.target = target;
            } else if (entity.getStandardURN().equals(BLOCKADE)) {
                this.target = ((Blockade) entity).getPosition();
            } else if (entity instanceof Building) {
                this.target = target;
            }
        }
        return this;
    }

    @Override
    public ExtAction calc() {
        this.result = null;
        PoliceForce policeForce = (PoliceForce) this.agentInfo.me();

        if (this.needRest(policeForce)) {
            List<EntityID> list = new ArrayList<>();
            if (this.target != null) {
                list.add(this.target);
            }
            this.result = this.calcRest(policeForce, this.pathPlanning, list);
            if (this.result != null) {
                return this;
            }
        }

        if (this.target == null) {
            return this;
        }

        EntityID agentPosition = policeForce.getPosition();
        StandardEntity targetEntity = this.worldInfo.getEntity(this.target);
        StandardEntity positionEntity = Objects.requireNonNull(this.worldInfo.getEntity(agentPosition));

        if (targetEntity == null || !(targetEntity instanceof Area)) {
            return this;
        }

        if (positionEntity instanceof Road) {
            this.result = this.getRescueAction(policeForce, (Road) positionEntity);
            if (this.result != null) return this;
        }

        if (agentPosition.equals(this.target)) {
            this.result = this.getAreaClearAction(policeForce, targetEntity);
        } else if (((Area) targetEntity).getEdgeTo(agentPosition) != null) {
            this.result = this.getNeighbourPositionAction(policeForce, (Area) targetEntity);
        } else {
            List<EntityID> path = this.pathPlanning.getResult(agentPosition, this.target);
            if (path != null && path.size() > 0) {
                int index = path.indexOf(agentPosition);
                if (index == -1) {
                    Area area = (Area) positionEntity;
                    for (int i = 0; i < path.size(); i++) {
                        if (area.getEdgeTo(path.get(i)) != null) {
                            index = i;
                            break;
                        }
                    }
                } else if (index >= 0) {
                    index++;
                }
                if (index >= 0 && index < (path.size())) {
                    StandardEntity entity = this.worldInfo.getEntity(path.get(index));
                    this.result = this.getNeighbourPositionAction(policeForce, (Area) entity);
                    if (this.result != null && this.result.getClass() == ActionMove.class) {
                        if (!((ActionMove) this.result).getUsePosition()) {
                            this.result = null;
                        }
                    }
                }
                if (this.result == null) {
                    this.result = new ActionMove(path);
                }
            }
        }

        // ========== 无效清理检测与强制移动 ==========
        if (this.target != null && this.worldInfo.getEntity(this.target) instanceof Road) {
            Road targetRoad = (Road) this.worldInfo.getEntity(this.target);
            int currentBlockadeCount = targetRoad.isBlockadesDefined() ? targetRoad.getBlockades().size() : -1;

            if (this.target.equals(lastClearTarget)) {
                if (currentBlockadeCount == lastBlockadeCount) {
                    invalidClearCounter++;
                    if (invalidClearCounter >= INVALID_CLEAR_LIMIT) {
                        System.err.println("[ActionExtClear] 警察 " + agentInfo.getID() +
                                " 在道路 " + this.target + " 连续无效清理 " + INVALID_CLEAR_LIMIT + " 次，强制放弃");
                        invalidClearCounter = 0;
                        lastClearTarget = null;
                        EntityID fallbackRoad = findNearbyUnblockedRoad(policeForce);
                        if (fallbackRoad != null) {
                            List<EntityID> path = pathPlanning.getResult(agentPosition, fallbackRoad);
                            if (path != null && !path.isEmpty()) {
                                this.result = new ActionMove(path);
                                return this;
                            }
                        }
                        this.result = new ActionRest();
                        return this;
                    }
                } else {
                    invalidClearCounter = 0;
                }
            } else {
                invalidClearCounter = 0;
            }
            lastClearTarget = this.target;
            lastBlockadeCount = currentBlockadeCount;
        }

        return this;
    }

    private EntityID findNearbyUnblockedRoad(PoliceForce police) {
        EntityID currentPos = police.getPosition();
        StandardEntity posEntity = worldInfo.getEntity(currentPos);
        if (posEntity instanceof Road) {
            Road road = (Road) posEntity;
            for (EntityID neighbour : road.getNeighbours()) {
                if (worldInfo.getEntity(neighbour) instanceof Road) {
                    Road nbRoad = (Road) worldInfo.getEntity(neighbour);
                    if (!nbRoad.isBlockadesDefined() || nbRoad.getBlockades().isEmpty()) {
                        return neighbour;
                    }
                }
            }
        }
        return null;
    }

    // ── 所有清理动作均已改为整体清理 ──

    private Action getRescueAction(PoliceForce police, Road road) {
        if (!road.isBlockadesDefined()) return null;

        List<Blockade> blockades = this.worldInfo.getBlockades(road)
                .stream().filter(Blockade::isApexesDefined).collect(Collectors.toList());
        if (blockades.isEmpty()) return null;

        Collection<StandardEntity> agents = this.worldInfo.getEntitiesOfType(
                AMBULANCE_TEAM, FIRE_BRIGADE);

        double policeX = police.getX();
        double policeY = police.getY();

        for (StandardEntity entity : agents) {
            Human human = (Human) entity;
            if (!human.isPositionDefined() || human.getPosition().getValue() != road.getID().getValue()) {
                continue;
            }
            double humanX = human.getX();
            double humanY = human.getY();

            for (Blockade blockade : blockades) {
                if (!this.isInside(humanX, humanY, blockade.getApexes())) continue;

                if (this.intersect(policeX, policeY, humanX, humanY, road)) {
                    return new ActionClear(blockade);
                }
                if (this.intersect(policeX, policeY, humanX, humanY, blockade)) {
                    return new ActionClear(blockade);
                }
            }
        }
        return null;
    }

    private Action getAreaClearAction(PoliceForce police, StandardEntity targetEntity) {
        if (targetEntity instanceof Building) return null;

        Road road = (Road) targetEntity;
        if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) return null;

        List<Blockade> blockades = this.worldInfo.getBlockades(road)
                .stream().filter(Blockade::isApexesDefined).collect(Collectors.toList());
        if (blockades.isEmpty()) return null;

        double agentX = police.getX();
        double agentY = police.getY();

        Blockade closest = null;
        double minDist = Double.MAX_VALUE;
        for (Blockade b : blockades) {
            double d = getDistance(agentX, agentY, b.getX(), b.getY());
            if (d < minDist) {
                minDist = d;
                closest = b;
            }
        }

        if (closest != null) {
            return new ActionClear(closest);
        }
        return null;
    }

    private Action getNeighbourPositionAction(PoliceForce police, Area target) {
        double agentX = police.getX();
        double agentY = police.getY();
        StandardEntity position = Objects.requireNonNull(this.worldInfo.getPosition(police));

        if (position instanceof Road) {
            Road road = (Road) position;
            if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                Blockade nearest = null;
                double nearestDist = Double.MAX_VALUE;
                for (Blockade b : this.worldInfo.getBlockades(road)) {
                    if (!b.isApexesDefined()) continue;
                    double d = getDistance(agentX, agentY, b.getX(), b.getY());
                    if (d < nearestDist) {
                        nearestDist = d;
                        nearest = b;
                    }
                }
                if (nearest != null) {
                    return new ActionClear(nearest);
                }
            }
        }

        if (target instanceof Road) {
            Road road = (Road) target;
            if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                Blockade nearest = null;
                double nearestDist = Double.MAX_VALUE;
                for (Blockade b : this.worldInfo.getBlockades(road)) {
                    if (!b.isApexesDefined()) continue;
                    double d = getDistance(agentX, agentY, b.getX(), b.getY());
                    if (d < nearestDist) {
                        nearestDist = d;
                        nearest = b;
                    }
                }
                if (nearest != null) {
                    return new ActionClear(nearest);
                }
            }
            return new ActionMove(Lists.newArrayList(position.getID(), target.getID()));
        }

        return new ActionMove(Lists.newArrayList(position.getID(), target.getID()));
    }

    // ── 几何工具方法保持不变 ──

    private boolean equalsPoint(double p1X, double p1Y, double p2X, double p2Y) {
        return this.equalsPoint(p1X, p1Y, p2X, p2Y, 1000.0D);
    }

    private boolean equalsPoint(double p1X, double p1Y, double p2X, double p2Y, double range) {
        return (p2X - range < p1X && p1X < p2X + range) && (p2Y - range < p1Y && p1Y < p2Y + range);
    }

    private boolean isInside(double pX, double pY, int[] apex) {
        Point2D p = new Point2D(pX, pY);
        Vector2D v1 = (new Point2D(apex[apex.length - 2], apex[apex.length - 1])).minus(p);
        Vector2D v2 = (new Point2D(apex[0], apex[1])).minus(p);
        double theta = this.getAngle(v1, v2);
        for (int i = 0; i < apex.length - 2; i += 2) {
            v1 = (new Point2D(apex[i], apex[i + 1])).minus(p);
            v2 = (new Point2D(apex[i + 2], apex[i + 3])).minus(p);
            theta += this.getAngle(v1, v2);
        }
        return Math.round(Math.abs((theta / 2) / Math.PI)) >= 1;
    }

    private boolean intersect(double agentX, double agentY, double pointX, double pointY, Area area) {
        for (Edge edge : area.getEdges()) {
            double startX = edge.getStartX();
            double startY = edge.getStartY();
            double endX = edge.getEndX();
            double endY = edge.getEndY();
            if (java.awt.geom.Line2D.linesIntersect(agentX, agentY, pointX, pointY, startX, startY, endX, endY)) {
                double midX = (edge.getStartX() + edge.getEndX()) / 2;
                double midY = (edge.getStartY() + edge.getEndY()) / 2;
                if (!equalsPoint(pointX, pointY, midX, midY) && !equalsPoint(agentX, agentY, midX, midY)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean intersect(Blockade blockade, Blockade another) {
        if (blockade.isApexesDefined() && another.isApexesDefined()) {
            int[] apexes0 = blockade.getApexes();
            int[] apexes1 = another.getApexes();
            for (int i = 0; i < (apexes0.length - 2); i += 2) {
                for (int j = 0; j < (apexes1.length - 2); j += 2) {
                    if (java.awt.geom.Line2D.linesIntersect(apexes0[i], apexes0[i + 1], apexes0[i + 2], apexes0[i + 3],
                            apexes1[j], apexes1[j + 1], apexes1[j + 2], apexes1[j + 3])) {
                        return true;
                    }
                }
            }
            for (int i = 0; i < (apexes0.length - 2); i += 2) {
                if (java.awt.geom.Line2D.linesIntersect(apexes0[i], apexes0[i + 1], apexes0[i + 2], apexes0[i + 3],
                        apexes1[apexes1.length - 2], apexes1[apexes1.length - 1], apexes1[0], apexes1[1])) {
                    return true;
                }
            }
            for (int j = 0; j < (apexes1.length - 2); j += 2) {
                if (java.awt.geom.Line2D.linesIntersect(apexes0[apexes0.length - 2], apexes0[apexes0.length - 1],
                        apexes0[0], apexes0[1], apexes1[j], apexes1[j + 1], apexes1[j + 2], apexes1[j + 3])) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean intersect(double agentX, double agentY, double pointX, double pointY, Blockade blockade) {
        List<Line2D> lines = GeometryTools2D.pointsToLines(
                GeometryTools2D.vertexArrayToPoints(blockade.getApexes()), true);
        for (Line2D line : lines) {
            Point2D start = line.getOrigin();
            Point2D end = line.getEndPoint();
            double startX = start.getX();
            double startY = start.getY();
            double endX = end.getX();
            double endY = end.getY();
            if (java.awt.geom.Line2D.linesIntersect(agentX, agentY, pointX, pointY, startX, startY, endX, endY)) {
                return true;
            }
        }
        return false;
    }

    private double getDistance(double fromX, double fromY, double toX, double toY) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        return Math.hypot(dx, dy);
    }

    private double getAngle(Vector2D v1, Vector2D v2) {
        double flag = (v1.getX() * v2.getY()) - (v1.getY() * v2.getX());
        double angle = Math.acos(((v1.getX() * v2.getX()) + (v1.getY() * v2.getY())) /
                (v1.getLength() * v2.getLength()));
        if (flag > 0) return angle;
        if (flag < 0) return -1 * angle;
        return 0.0D;
    }

    private Vector2D getVector(double fromX, double fromY, double toX, double toY) {
        return (new Point2D(toX, toY)).minus(new Point2D(fromX, fromY));
    }

    private Vector2D scaleClear(Vector2D vector) {
        return vector.normalised().scale(this.clearDistance);
    }

    private Vector2D scaleBackClear(Vector2D vector) {
        return vector.normalised().scale(-510);
    }

    private Set<Point2D> getMovePoints(Road road) {
        Set<Point2D> points = this.movePointCache.get(road.getID());
        if (points == null) {
            points = new HashSet<>();
            int[] apex = road.getApexList();
            for (int i = 0; i < apex.length; i += 2) {
                for (int j = i + 2; j < apex.length; j += 2) {
                    double midX = (apex[i] + apex[j]) / 2;
                    double midY = (apex[i + 1] + apex[j + 1]) / 2;
                    if (this.isInside(midX, midY, apex)) {
                        points.add(new Point2D(midX, midY));
                    }
                }
            }
            for (Edge edge : road.getEdges()) {
                double midX = (edge.getStartX() + edge.getEndX()) / 2;
                double midY = (edge.getStartY() + edge.getEndY()) / 2;
                points.remove(new Point2D(midX, midY));
            }
            this.movePointCache.put(road.getID(), points);
        }
        return points;
    }

    private boolean needRest(Human agent) {
        int hp = agent.getHP();
        int damage = agent.getDamage();
        if (damage == 0 || hp == 0) {
            return false;
        }
        int activeTime = (hp / damage) + ((hp % damage) != 0 ? 1 : 0);
        if (this.kernelTime == -1) {
            try {
                this.kernelTime = this.scenarioInfo.getKernelTimesteps();
            } catch (NoSuchConfigOptionException e) {
                this.kernelTime = -1;
            }
        }
        return damage >= this.thresholdRest
                || (activeTime + this.agentInfo.getTime()) < this.kernelTime;
    }

    private Action calcRest(Human human, PathPlanning pathPlanning, Collection<EntityID> targets) {
        EntityID position = human.getPosition();
        Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
        int currentSize = refuges.size();
        if (refuges.contains(position)) {
            return new ActionRest();
        }
        List<EntityID> firstResult = null;
        while (refuges.size() > 0) {
            pathPlanning.setFrom(position);
            pathPlanning.setDestination(refuges);
            List<EntityID> path = pathPlanning.calc().getResult();
            if (path != null && path.size() > 0) {
                if (firstResult == null) {
                    firstResult = new ArrayList<>(path);
                    if (targets == null || targets.isEmpty()) {
                        break;
                    }
                }
                EntityID refugeID = path.get(path.size() - 1);
                pathPlanning.setFrom(refugeID);
                pathPlanning.setDestination(targets);
                List<EntityID> fromRefugeToTarget = pathPlanning.calc().getResult();
                if (fromRefugeToTarget != null && fromRefugeToTarget.size() > 0) {
                    return new ActionMove(path);
                }
                refuges.remove(refugeID);
                if (currentSize == refuges.size()) {
                    break;
                }
                currentSize = refuges.size();
            } else {
                break;
            }
        }
        return firstResult != null ? new ActionMove(firstResult) : null;
    }
}