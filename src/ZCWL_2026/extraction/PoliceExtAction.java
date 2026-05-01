package ZCWL_2026.extraction;

import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.police.ActionClear;
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

import java.awt.geom.Point2D;
import java.util.*;

/**
 * 警车清障动作 - 基于盾构机思想 (ZCWL_2026 版)
 * 去除所有日志及外部特化模块依赖，保留核心清障与卡死处理逻辑
 */
public class PoliceExtAction extends ExtAction {

    // 走廊参数
    private static final double CORRIDOR_LENGTH_FACTOR = 1.2;
    private static final double STEP_DISTANCE_FACTOR = 0.6;
    private static final double SAFE_MARGIN = 500.0;
    private static final double STUCK_MOVE_THRESHOLD = 500.0;
    private static final double OPTIMAL_CLEAR_DIST = 5000.0;
    private static final int CLEAR_DISTANCE_BUFFER = 1000;

    // 卡死检测与干预
    private int stuckCyclesBeforeClear = 4;
    private static final int BERLIN_BACKOFF_INTERVENTIONS = 3;
    private static final double INTERVENE_PROGRESS_MM = 1000.0;
    private static final double POLICE_CLEAR_RADIUS = 15000.0; // 替代 mapSpec.getPoliceClearRad()

    private PathPlanning pathPlanning;
    private EntityID target;

    // Executor 模式用
    private List<EntityID> currentPath;
    private boolean didTunnelingLastCycle;
    private Point2D.Double safeStepTarget;

    // 卡死检测状态
    private double lastCalcX = Double.NaN;
    private double lastCalcY = Double.NaN;
    private int stuckCycleCount;
    private int lastClearTime = -1;
    private int lastStuckCheckTime = -1;

    // Berlin 退避
    private int interveneWithoutMoveCount;
    private final int clearRepairDistance;

    public PoliceExtAction(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                           ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        this.pathPlanning = mm.getModule(
                "PoliceExtAction.PathPlanning",
                "ZCWL_2026.module.algorithm.PathPlanning");
        this.clearRepairDistance = si.getClearRepairDistance();
        this.stuckCyclesBeforeClear = 4; // 默认值
    }

    @Override
    public ExtAction setTarget(EntityID id) {
        if (this.target == null || !this.target.equals(id)) {
            this.stuckCycleCount = 0;
        }
        this.target = id;
        this.currentPath = null;
        return this;
    }

    public PoliceExtAction setTarget(EntityID id, List<EntityID> path) {
        if (this.target == null || !this.target.equals(id)) {
            this.stuckCycleCount = 0;
        }
        this.target = id;
        this.currentPath = path;
        return this;
    }

    public boolean consumeTunnelingState() {
        boolean state = this.didTunnelingLastCycle;
        this.didTunnelingLastCycle = false;
        return state;
    }

    public Point2D.Double getSafeStepTarget() {
        return this.safeStepTarget;
    }

    @Override
    public Action getAction() {
        return this.result;
    }

    @Override
    public ExtAction calc() {
        this.result = null;
        this.safeStepTarget = null;

        int currentTime = this.agentInfo.getTime();

        if (this.target == null) return this;

        PoliceForce me = (PoliceForce) this.agentInfo.me();
        EntityID myPos = me.getPosition();
        if (myPos == null) return this;

        StandardEntity te = this.worldInfo.getEntity(this.target);
        if (te == null) return this;

        double agentX = me.getX();
        double agentY = me.getY();

        // 卡死检测
        if (currentTime != this.lastStuckCheckTime) {
            this.lastStuckCheckTime = currentTime;
            boolean prevActionWasClear = (this.lastClearTime == currentTime - 1);
            if (!Double.isNaN(this.lastCalcX)) {
                double moved = Math.hypot(agentX - this.lastCalcX, agentY - this.lastCalcY);
                if (moved >= STUCK_MOVE_THRESHOLD) {
                    this.stuckCycleCount = 0;
                } else if (!prevActionWasClear) {
                    this.stuckCycleCount++;
                }
                if (prevActionWasClear) {
                    if (moved < INTERVENE_PROGRESS_MM) {
                        this.interveneWithoutMoveCount++;
                    } else {
                        this.interveneWithoutMoveCount = 0;
                    }
                } else if (moved >= STUCK_MOVE_THRESHOLD) {
                    this.interveneWithoutMoveCount = 0;
                }
            }
            this.lastCalcX = agentX;
            this.lastCalcY = agentY;
        }

        // Berlin 退避 (无地图特化，保持通用)
        if (this.interveneWithoutMoveCount >= BERLIN_BACKOFF_INTERVENTIONS) {
            Action backoff = buildBerlinBackoffMove(myPos, agentX, agentY);
            if (backoff != null) {
                this.result = backoff;
                this.interveneWithoutMoveCount = 0;
                this.stuckCycleCount = 0;
                this.didTunnelingLastCycle = false;
                return this;
            }
        }

        // Opportunistic clear 顺手清
        EntityID curPos = me.getPosition();
        boolean targetIsCurrentRoad = false;
        if (te instanceof Area && this.target.equals(curPos)) {
            targetIsCurrentRoad = true;
        } else if (te instanceof Blockade) {
            EntityID bp = ((Blockade) te).getPosition();
            if (bp != null && bp.equals(curPos)) targetIsCurrentRoad = true;
        }
        if (!targetIsCurrentRoad && this.worldInfo.getEntity(curPos) instanceof Road) {
            Blockade opp = pickOpportunisticBlockade(curPos, agentX, agentY);
            if (opp != null) {
                this.result = new ActionClear(opp);
                this.lastClearTime = currentTime;
                return this;
            }
        }

        EntityID targetRoadId;
        if (te instanceof Blockade) {
            EntityID pos = ((Blockade) te).getPosition();
            if (pos == null) return this;
            targetRoadId = pos;
        } else if (te instanceof Area) {
            targetRoadId = this.target;
        } else {
            return this;
        }

        if (this.currentPath != null) {
            calcTunnelMode(myPos, agentX, agentY, targetRoadId);
        } else {
            calcApexMode(myPos, agentX, agentY, targetRoadId);
        }
        return this;
    }

    private void calcTunnelMode(EntityID myPos, double agentX, double agentY, EntityID targetRoadId) {
        double corridorLength = this.clearRepairDistance * CORRIDOR_LENGTH_FACTOR;
        double stepDist = this.clearRepairDistance * STEP_DISTANCE_FACTOR;
        int clearThreshold = this.clearRepairDistance - CLEAR_DISTANCE_BUFFER;

        double[] dir = computeForwardDirection(agentX, agentY, myPos, targetRoadId, this.currentPath);
        if (dir == null) {
            calcApexMode(myPos, agentX, agentY, targetRoadId);
            return;
        }

        Set<EntityID> roadsToScan = new LinkedHashSet<>();
        roadsToScan.add(myPos);
        roadsToScan.add(targetRoadId);
        if (this.currentPath != null) {
            for (EntityID id : this.currentPath) {
                if (id != null && this.worldInfo.getEntity(id) instanceof Road)
                    roadsToScan.add(id);
            }
        }

        Blockade blocking = findBlockadeInCorridorMultiRoad(agentX, agentY, dir, roadsToScan, corridorLength);
        EntityID nextNode = findNextPathNode(myPos, targetRoadId, this.currentPath);
        StandardEntity nextEntity = (nextNode != null) ? this.worldInfo.getEntity(nextNode) : null;
        Area nextArea = (nextEntity instanceof Area) ? (Area) nextEntity : null;

        if (blocking != null) {
            double distToCentroid = nearestApexDistance(blocking, agentX, agentY);
            if (distToCentroid <= clearThreshold) {
                this.result = new ActionClear(blocking);
                this.lastClearTime = this.agentInfo.getTime();
                this.safeStepTarget = new Point2D.Double(agentX + dir[0] * stepDist, agentY + dir[1] * stepDist);
                return;
            }

            if (this.stuckCycleCount >= stuckCyclesBeforeClear) {
                int fireX = (nextArea != null) ? nextArea.getX() : (int) (agentX + dir[0] * 5000.0);
                int fireY = (nextArea != null) ? nextArea.getY() : (int) (agentY + dir[1] * 5000.0);
                this.result = new ActionClear(fireX, fireY);
                this.didTunnelingLastCycle = true;
                this.lastClearTime = this.agentInfo.getTime();
                this.safeStepTarget = new Point2D.Double(agentX + dir[0] * stepDist, agentY + dir[1] * stepDist);
                this.stuckCycleCount = 0;
                return;
            }

            List<EntityID> approachPath = (this.currentPath != null && !this.currentPath.isEmpty())
                    ? this.currentPath : Collections.singletonList(targetRoadId);
            double[] nap = nearestApexPoint(blocking, agentX, agentY);
            this.result = new ActionMove(approachPath, (int) nap[0], (int) nap[1]);
            return;
        }

        StandardEntity targetAreaEntity = this.worldInfo.getEntity(targetRoadId);
        if (targetAreaEntity instanceof Area) {
            double distToTarget = Math.hypot(agentX - ((Area) targetAreaEntity).getX(),
                    agentY - ((Area) targetAreaEntity).getY());
            if (distToTarget > clearThreshold) {
                if (this.stuckCycleCount >= stuckCyclesBeforeClear) {
                    Blockade stuckBlockade = findMostBlockingBlockade(myPos, agentX, agentY);
                    if (stuckBlockade != null) {
                        double safeClearDist = this.clearRepairDistance - CLEAR_DISTANCE_BUFFER;
                        double dist = nearestApexDistance(stuckBlockade, agentX, agentY);
                        if (dist <= safeClearDist) {
                            double[] nearestV = nearestApexPoint(stuckBlockade, agentX, agentY);
                            double vDx = nearestV[0] - agentX;
                            double vDy = nearestV[1] - agentY;
                            double vDist = Math.hypot(vDx, vDy);
                            int clearX = (int) Math.round(agentX + (vDx / vDist) * safeClearDist);
                            int clearY = (int) Math.round(agentY + (vDy / vDist) * safeClearDist);
                            this.result = new ActionClear(clearX, clearY, stuckBlockade);
                            this.didTunnelingLastCycle = true;
                            this.lastClearTime = this.agentInfo.getTime();
                            this.safeStepTarget = new Point2D.Double(agentX + dir[0] * stepDist, agentY + dir[1] * stepDist);
                            return;
                        }
                    }

                    int fireX = (nextArea != null) ? nextArea.getX() : (int) (agentX + dir[0] * 5000.0);
                    int fireY = (nextArea != null) ? nextArea.getY() : (int) (agentY + dir[1] * 5000.0);
                    this.result = new ActionClear(fireX, fireY);
                    this.didTunnelingLastCycle = true;
                    this.lastClearTime = this.agentInfo.getTime();
                    this.safeStepTarget = new Point2D.Double(agentX + dir[0] * stepDist, agentY + dir[1] * stepDist);
                    return;
                }
                this.result = buildMove(this.currentPath, myPos);
                return;
            }
        }

        calcApexMode(myPos, agentX, agentY, targetRoadId);
    }

    private void calcApexMode(EntityID myPos, double agentX, double agentY, EntityID targetRoadId) {
        Blockade blockadeToClear = null;
        StandardEntity te = this.worldInfo.getEntity(this.target);
        if (te instanceof Blockade && ((Blockade) te).isApexesDefined()) {
            blockadeToClear = (Blockade) te;
        }

        if (blockadeToClear == null || !blockadeToClear.isApexesDefined()) {
            blockadeToClear = findMostBlockingBlockade(targetRoadId, agentX, agentY);
        }

        if (blockadeToClear != null && blockadeToClear.isApexesDefined()) {
            int clearThreshold = this.clearRepairDistance - CLEAR_DISTANCE_BUFFER;
            double distToBlockade = nearestApexDistance(blockadeToClear, agentX, agentY);
            if (distToBlockade <= clearThreshold) {
                this.result = new ActionClear(blockadeToClear);
                this.lastClearTime = this.agentInfo.getTime();
                return;
            }
            EntityID blockadeRoadId = blockadeToClear.getPosition();
            if (blockadeRoadId != null) targetRoadId = blockadeRoadId;
        }

        List<EntityID> path = this.pathPlanning
                .setFrom(myPos)
                .setDestination(Collections.singleton(targetRoadId))
                .calc()
                .getResult();

        if (this.stuckCycleCount >= stuckCyclesBeforeClear) {
            double[] dir = getCentroidDirection(agentX, agentY, targetRoadId);
            if (dir != null) {
                int fireX = (int) (agentX + dir[0] * 5000.0);
                int fireY = (int) (agentY + dir[1] * 5000.0);
                this.result = new ActionClear(fireX, fireY);
                this.lastClearTime = this.agentInfo.getTime();
                this.stuckCycleCount = 0;
                return;
            }
        }

        if (blockadeToClear != null) {
            List<EntityID> approachPath = (path != null && !path.isEmpty()) ? path : Collections.singletonList(targetRoadId);
            double[] nap = nearestApexPoint(blockadeToClear, agentX, agentY);
            this.result = new ActionMove(approachPath, (int) nap[0], (int) nap[1]);
        } else {
            this.result = buildMove(path, myPos);
            if (this.result == null && !myPos.equals(targetRoadId)) {
                this.result = new ActionMove(Collections.singletonList(targetRoadId));
            }
        }
    }

    private Blockade findBlockadeInCorridorMultiRoad(double agentX, double agentY, double[] dir,
                                                     Set<EntityID> roadIds, double corridorLength) {
        double dx = dir[0], dy = dir[1];
        double px = -dy, py = dx;
        Blockade found = null;
        double minFwd = Double.MAX_VALUE;

        for (EntityID roadId : roadIds) {
            for (EntityID bid : getBlockadesOnRoad(roadId)) {
                StandardEntity se = this.worldInfo.getEntity(bid);
                if (!(se instanceof Blockade) || !((Blockade) se).isApexesDefined()) continue;
                Blockade b = (Blockade) se;
                int[] apexes = b.getApexes();
                if (apexes == null || apexes.length < 4) continue;
                double bestFwd = checkBlockadeInCorridor(apexes, agentX, agentY, dx, dy, px, py, corridorLength, POLICE_CLEAR_RADIUS);
                if (bestFwd >= 0 && bestFwd < minFwd) {
                    minFwd = bestFwd;
                    found = b;
                }
            }
        }
        return found;
    }

    private double checkBlockadeInCorridor(int[] apexes, double agentX, double agentY,
                                           double dx, double dy, double px, double py,
                                           double corridorLength, double clearRadius) {
        double bestFwd = -1;
        int n = apexes.length;
        final double REAR_MARGIN = -2500.0;

        for (int i = 0; i < n - 1; i += 2) {
            double relX = apexes[i] - agentX;
            double relY = apexes[i + 1] - agentY;
            double fwd = relX * dx + relY * dy;
            double lat = relX * px + relY * py;
            if (fwd >= REAR_MARGIN && fwd <= corridorLength && Math.abs(lat) <= clearRadius) {
                if (bestFwd < 0 || fwd < bestFwd) bestFwd = fwd;
            }
        }
        if (bestFwd >= REAR_MARGIN) return bestFwd;

        for (int i = 0; i < n - 1; i += 2) {
            int ni = (i + 2) % n;
            double ax1 = apexes[i] - agentX;
            double ay1 = apexes[i + 1] - agentY;
            double ax2 = apexes[ni] - agentX;
            double ay2 = apexes[ni + 1] - agentY;
            double fwd1 = ax1 * dx + ay1 * dy;
            double lat1 = ax1 * px + ay1 * py;
            double fwd2 = ax2 * dx + ay2 * dy;
            double lat2 = ax2 * px + ay2 * py;
            double hitFwd = segmentCorridorIntersection(fwd1, lat1, fwd2, lat2, corridorLength, clearRadius);
            if (hitFwd >= 0 && (bestFwd < 0 || hitFwd < bestFwd)) bestFwd = hitFwd;
        }
        return bestFwd;
    }

    private double segmentCorridorIntersection(double fwd1, double lat1, double fwd2, double lat2,
                                               double corridorLen, double clearRadius) {
        double tMin = 0.0, tMax = 1.0;
        double dFwd = fwd2 - fwd1;
        double dLat = lat2 - lat1;

        if (Math.abs(dFwd) > 1e-9) {
            double t1 = (0 - fwd1) / dFwd;
            double t2 = (corridorLen - fwd1) / dFwd;
            if (dFwd < 0) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        } else {
            if (fwd1 < 0 || fwd1 > corridorLen) return -1;
        }

        if (Math.abs(dLat) > 1e-9) {
            double t1 = (-clearRadius - lat1) / dLat;
            double t2 = (clearRadius - lat1) / dLat;
            if (dLat < 0) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        } else {
            if (lat1 < -clearRadius || lat1 > clearRadius) return -1;
        }

        double hitFwd = fwd1 + tMin * dFwd;
        if (hitFwd < 0) hitFwd = 0;
        return hitFwd;
    }

    private Blockade pickOpportunisticBlockade(EntityID myPos, double agentX, double agentY) {
        int clearThreshold = this.clearRepairDistance - CLEAR_DISTANCE_BUFFER;
        Blockade nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (EntityID bid : getBlockadesOnRoad(myPos)) {
            StandardEntity se = this.worldInfo.getEntity(bid);
            if (!(se instanceof Blockade) || !((Blockade) se).isApexesDefined()) continue;
            Blockade b = (Blockade) se;
            double d = nearestApexDistance(b, agentX, agentY);
            if (d <= clearThreshold && d < nearestDist) { nearestDist = d; nearest = b; }
        }
        return nearest;
    }

    private Blockade findMostBlockingBlockade(EntityID roadId, double agentX, double agentY) {
        Blockade best = null;
        double bestScore = -1;
        for (EntityID bid : getBlockadesOnRoad(roadId)) {
            StandardEntity se = this.worldInfo.getEntity(bid);
            if (!(se instanceof Blockade) || !((Blockade) se).isApexesDefined()) continue;
            Blockade b = (Blockade) se;
            double dist = nearestApexDistance(b, agentX, agentY);
            int repairCost = b.isRepairCostDefined() ? b.getRepairCost() : 1;
            double score = repairCost / (dist + 1.0);
            if (score > bestScore) { bestScore = score; best = b; }
        }
        return best;
    }

    private double[] computeForwardDirection(double agentX, double agentY, EntityID myPos, EntityID targetRoadId, List<EntityID> path) {
        EntityID nextNode = findNextPathNode(myPos, targetRoadId, path);
        return getCentroidDirection(agentX, agentY, nextNode);
    }

    private EntityID findNextPathNode(EntityID myPos, EntityID targetRoadId, List<EntityID> path) {
        if (path != null) {
            boolean passed = false;
            for (EntityID id : path) {
                if (passed && !id.equals(myPos)) return id;
                if (id.equals(myPos)) passed = true;
            }
            for (EntityID id : path) {
                if (!id.equals(myPos)) return id;
            }
        }
        return targetRoadId;
    }

    private double[] getCentroidDirection(double agentX, double agentY, EntityID targetId) {
        StandardEntity targetArea = this.worldInfo.getEntity(targetId);
        if (targetArea instanceof Area) {
            double dx = ((Area) targetArea).getX() - agentX;
            double dy = ((Area) targetArea).getY() - agentY;
            double dist = Math.hypot(dx, dy);
            if (dist > 1.0) return new double[]{dx / dist, dy / dist};
        }
        return null;
    }

    private List<EntityID> getBlockadesOnRoad(EntityID roadId) {
        StandardEntity se = this.worldInfo.getEntity(roadId);
        if (se instanceof Road) {
            Road road = (Road) se;
            if (road.isBlockadesDefined() && road.getBlockades() != null)
                return road.getBlockades();
        }
        return Collections.emptyList();
    }

    private double nearestApexDistance(Blockade b, double ax, double ay) {
        int[] apexes = b.getApexes();
        if (apexes == null || apexes.length < 2)
            return b.isXDefined() ? Math.hypot(ax - b.getX(), ay - b.getY()) : Double.MAX_VALUE;
        double min = Double.MAX_VALUE;
        for (int i = 0; i < apexes.length - 1; i += 2) {
            double d = Math.hypot(ax - apexes[i], ay - apexes[i + 1]);
            if (d < min) min = d;
        }
        return min;
    }

    private double[] nearestApexPoint(Blockade b, double ax, double ay) {
        int[] apexes = b.getApexes();
        if (apexes == null || apexes.length < 2)
            return b.isXDefined() ? new double[]{b.getX(), b.getY()} : null;
        double minDist = Double.MAX_VALUE, bestX = apexes[0], bestY = apexes[1];
        for (int i = 0; i < apexes.length - 1; i += 2) {
            double d = Math.hypot(ax - apexes[i], ay - apexes[i + 1]);
            if (d < minDist) { minDist = d; bestX = apexes[i]; bestY = apexes[i + 1]; }
        }
        return new double[]{bestX, bestY};
    }

    private ActionMove buildMove(List<EntityID> path, EntityID myPos) {
        if (path == null || path.isEmpty()) return null;
        if (path.get(0).equals(myPos)) path = path.subList(1, path.size());
        return path.isEmpty() ? null : new ActionMove(path);
    }

    private Action buildBerlinBackoffMove(EntityID myPos, double agentX, double agentY) {
        StandardEntity here = this.worldInfo.getEntity(myPos);
        if (!(here instanceof Area)) return null;
        EntityID nextNode = findNextPathNode(myPos, this.target, this.currentPath);
        EntityID forbidden = (this.target != null && this.worldInfo.getEntity(this.target) instanceof Area) ? this.target : null;
        EntityID best = null;
        double bestDist = -1.0;
        for (EntityID nid : ((Area) here).getNeighbours()) {
            if (nid == null || nid.equals(myPos) || nid.equals(nextNode) || nid.equals(forbidden)) continue;
            StandardEntity ne = this.worldInfo.getEntity(nid);
            if (!(ne instanceof Road)) continue;
            Area na = (Area) ne;
            double dist = (forbidden != null && this.worldInfo.getEntity(forbidden) instanceof Area)
                    ? Math.hypot(na.getX() - ((Area) this.worldInfo.getEntity(forbidden)).getX(),
                                 na.getY() - ((Area) this.worldInfo.getEntity(forbidden)).getY())
                    : Math.hypot(na.getX() - agentX, na.getY() - agentY);
            if (dist > bestDist) { bestDist = dist; best = nid; }
        }
        if (best == null) return null;
        return new ActionMove(Collections.singletonList(best));
    }

    @Override
    public ExtAction precompute(PrecomputeData pd) { return this; }
    @Override
    public ExtAction resume(PrecomputeData pd) { return this; }
    @Override
    public ExtAction preparate() { return this; }
    @Override
    public ExtAction updateInfo(adf.core.agent.communication.MessageManager mm) { return this; }
}