package ZCWL_2026.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;

public class PoliceTargetAllocator extends adf.core.component.module.complex.PoliceTargetAllocator {

    private static final int PRIORITY_FORCED_RESCUE = -1;
    private static final int PRIORITY_URGENT = 0;
    private static final int PRIORITY_RESCUE = 1;
    private static final int PRIORITY_RESCUE_UNDEFINED = 2;
    private static final int PRIORITY_CRITICAL = 3;

    private static final int MAX_POLICE_PER_TASK = 1;
    private static final int MAX_POLICE_PER_RESCUE_TASK = 1;
    private static final int TASK_COOLDOWN = 30;
    private static final int TASK_TIMEOUT = 100;
    private static final int MAX_SEARCH_DEPTH = 4;

    private PathPlanning pathPlanning;
    private Clustering policeClustering;
    private MessageManager msgManager;
    private int currentTime;

    private PriorityQueue<RoadTask> taskQueue;
    private Map<EntityID, Integer> taskAssignCount;
    private Map<EntityID, Integer> taskCooldown;
    private Map<EntityID, Integer> taskAssignTime;

    private Set<EntityID> urgentRequests;
    private Set<EntityID> rescueRoutes;
    private Map<EntityID, Integer> rescuePriority;
    private Set<EntityID> criticalRoads;

    private Set<EntityID> knownVictimBuildings;

    private Map<EntityID, EntityID> policePositions;
    private Map<EntityID, PoliceTaskInfo> policeTaskInfoMap;

    private Map<EntityID, EntityID> allocationResult;
    private Map<EntityID, EntityID> policeCurrentTask;
    private Map<EntityID, Integer> policeTaskStartTime;

    public PoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                 ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);

        this.taskQueue = new PriorityQueue<>((a, b) -> {
            if (a.priority != b.priority) return Integer.compare(a.priority, b.priority);
            return Double.compare(a.distance, b.distance);
        });
        this.taskAssignCount = new HashMap<>();
        this.taskCooldown = new HashMap<>();
        this.taskAssignTime = new HashMap<>();
        this.urgentRequests = new HashSet<>();
        this.rescueRoutes = new HashSet<>();
        this.rescuePriority = new HashMap<>();
        this.criticalRoads = new HashSet<>();
        this.knownVictimBuildings = new HashSet<>();
        this.policePositions = new HashMap<>();
        this.policeTaskInfoMap = new HashMap<>();
        this.allocationResult = new HashMap<>();
        this.policeCurrentTask = new HashMap<>();
        this.policeTaskStartTime = new HashMap<>();

        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = mm.getModule(
                    "PoliceTargetAllocator.PathPlanning",
                    "ZCWL_2026.module.algorithm.PathPlanning");
                this.policeClustering = mm.getModule(
                    "PoliceTargetAllocator.Clustering",
                    "ZCWL_2026.module.algorithm.PoliceBalancedClustering");
                break;
        }
        initCriticalRoads();
    }

    private void initCriticalRoads() {
        Set<EntityID> importantBuildings = new HashSet<>();
        for (StandardEntity e : worldInfo.getEntitiesOfType(REFUGE, FIRE_STATION, POLICE_OFFICE, AMBULANCE_CENTRE)) {
            if (e instanceof Building) {
                importantBuildings.add(e.getID());
            }
        }
        Set<EntityID> visited = new HashSet<>();
        Queue<EntityID> queue = new LinkedList<>();
        for (EntityID buildingId : importantBuildings) {
            Building b = (Building) worldInfo.getEntity(buildingId);
            if (b == null) continue;
            for (EntityID nb : b.getNeighbours()) {
                if (worldInfo.getEntity(nb) instanceof Road && !visited.contains(nb)) {
                    queue.add(nb);
                    visited.add(nb);
                }
            }
        }
        int depth = MAX_SEARCH_DEPTH;
        while (!queue.isEmpty() && depth-- > 0) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                EntityID roadId = queue.poll();
                Road road = (Road) worldInfo.getEntity(roadId);
                if (road == null) continue;
                boolean blocked = hasBlockades(roadId);
                boolean undefined = !blocked && !isRoadDefined(roadId);
                if (blocked || undefined) {
                    criticalRoads.add(roadId);
                    for (EntityID neighborId : road.getNeighbours()) {
                        if (worldInfo.getEntity(neighborId) instanceof Road && !visited.contains(neighborId)) {
                            visited.add(neighborId);
                            queue.add(neighborId);
                        }
                    }
                }
            }
        }
    }

    @Override
    public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (getCountUpdateInfo() >= 2) return this;
        this.msgManager = messageManager;
        this.currentTime = agentInfo.getTime();
        if (pathPlanning != null) pathPlanning.updateInfo(messageManager);

        policePositions.clear();
        for (StandardEntity e : worldInfo.getEntitiesOfType(POLICE_FORCE)) {
            PoliceForce p = (PoliceForce) e;
            if (p.isPositionDefined()) {
                policePositions.put(p.getID(), p.getPosition());
            }
        }

        taskCooldown.replaceAll((k, v) -> v - 1);
        taskCooldown.values().removeIf(v -> v <= 0);

        processMessages();
        refreshRescueRoutesFromKnownVictims();

        Iterator<Map.Entry<EntityID, Integer>> it = taskAssignTime.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<EntityID, Integer> e = it.next();
            if (currentTime - e.getValue() > TASK_TIMEOUT) {
                EntityID road = e.getKey();
                taskAssignCount.remove(road);
                taskCooldown.remove(road);
                it.remove();
                releasePoliceByRoad(road);
            }
        }

        Iterator<Map.Entry<EntityID, Integer>> policeIt = policeTaskStartTime.entrySet().iterator();
        while (policeIt.hasNext()) {
            Map.Entry<EntityID, Integer> entry = policeIt.next();
            if (currentTime - entry.getValue() > TASK_TIMEOUT) {
                EntityID policeId = entry.getKey();
                policeCurrentTask.remove(policeId);
                policeTaskInfoMap.remove(policeId);
                policeIt.remove();
            }
        }

        for (EntityID id : worldInfo.getChanged().getChangedEntities()) {
            StandardEntity e = worldInfo.getEntity(id);
            if (e instanceof Road) {
                Road r = (Road) e;
                if (r.isBlockadesDefined() && r.getBlockades().isEmpty()) {
                    removeTaskFromAllSources(id);
                    releasePoliceByRoad(id);
                }
            }
        }
        return this;
    }

    private void releasePoliceByRoad(EntityID roadId) {
        Iterator<Map.Entry<EntityID, EntityID>> it = policeCurrentTask.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<EntityID, EntityID> entry = it.next();
            if (entry.getValue().equals(roadId)) {
                EntityID policeId = entry.getKey();
                it.remove();
                policeTaskStartTime.remove(policeId);
                policeTaskInfoMap.remove(policeId);
            }
        }
    }

    private void processMessages() {
        for (CommunicationMessage msg : msgManager.getReceivedMessageList()) {
            if (msg instanceof MessageFireBrigade) {
                MessageFireBrigade mfb = (MessageFireBrigade) msg;
                if (mfb.getAction() == MessageFireBrigade.ACTION_MOVE && mfb.getTargetID() != null) {
                    handleMoveRequest(mfb.getAgentID(), mfb.getTargetID());
                }
                if (mfb.isBuriednessDefined() && mfb.getBuriedness() > 0 && mfb.isPositionDefined()) {
                    knownVictimBuildings.add(mfb.getPosition());
                }
            } else if (msg instanceof MessageAmbulanceTeam) {
                MessageAmbulanceTeam mat = (MessageAmbulanceTeam) msg;
                if (mat.getAction() == MessageAmbulanceTeam.ACTION_MOVE && mat.getTargetID() != null) {
                    handleMoveRequest(mat.getAgentID(), mat.getTargetID());
                }
                if (mat.isBuriednessDefined() && mat.getBuriedness() > 0 && mat.isPositionDefined()) {
                    knownVictimBuildings.add(mat.getPosition());
                }
            } else if (msg instanceof MessageCivilian) {
                MessageCivilian mc = (MessageCivilian) msg;
                if (mc.isBuriednessDefined() && mc.getBuriedness() > 0 && mc.isPositionDefined()) {
                    knownVictimBuildings.add(mc.getPosition());
                }
            } else if (msg instanceof MessagePoliceForce) {
                MessagePoliceForce mpf = (MessagePoliceForce) msg;
                if (mpf.isBuriednessDefined() && mpf.getBuriedness() > 0 && mpf.isPositionDefined()) {
                    knownVictimBuildings.add(mpf.getPosition());
                }
            } else if (msg instanceof MessageReport) {
                MessageReport report = (MessageReport) msg;
                if (report.isDone()) {
                    EntityID policeId = report.getSenderID();
                    if (policeId != null) {
                        EntityID currentRoad = policeCurrentTask.get(policeId);
                        if (currentRoad != null) {
                            policeCurrentTask.remove(policeId);
                            policeTaskStartTime.remove(policeId);
                            policeTaskInfoMap.remove(policeId);
                            removeTaskFromAllSources(currentRoad);
                        }
                    }
                }
            }
        }
    }

    /**
     * 核心修改：处理移动请求，将路径上所有需要清理的道路加入紧急任务
     */
    private void handleMoveRequest(EntityID agentId, EntityID targetId) {
        StandardEntity agentEntity = worldInfo.getEntity(agentId);
        if (!(agentEntity instanceof Human)) return;
        Human human = (Human) agentEntity;
        if (!human.isPositionDefined()) return;
        EntityID from = human.getPosition();
        EntityID to = targetId;

        List<EntityID> path = (pathPlanning != null) 
                ? pathPlanning.getResult(from, to) 
                : null;

        int addedCount = 0;
        if (path != null && !path.isEmpty()) {
            for (EntityID step : path) {
                if (worldInfo.getEntity(step) instanceof Road) {
                    if (needsClearing(step) && !taskCooldown.containsKey(step)) {
                        urgentRequests.add(step);
                        taskCooldown.put(step, TASK_COOLDOWN);
                        addedCount++;
                    }
                }
            }
        }
        // 保底：至少把目标道路加进去（如果目标本身是道路且需要清理）
        if (worldInfo.getEntity(to) instanceof Road) {
            if (needsClearing(to) && !taskCooldown.containsKey(to)) {
                urgentRequests.add(to);
                taskCooldown.put(to, TASK_COOLDOWN);
                addedCount++;
            }
        }

        if (addedCount > 0) {
            System.err.printf("[分配器] 紧急开路: 为 %s 从 %s 到 %s 添加 %d 条道路%n",
                    agentId, from, to, addedCount);
        }
    }

    private void refreshRescueRoutesFromKnownVictims() {
        for (EntityID building : knownVictimBuildings) {
            StandardEntity entity = worldInfo.getEntity(building);
            if (!(entity instanceof Building)) continue;
            addRescueRoutesFromBuilding(building);
        }
    }

    private void addRescueRoutesFromBuilding(EntityID building) {
        StandardEntity entity = worldInfo.getEntity(building);
        if (!(entity instanceof Building)) return;
        Building b = (Building) entity;

        Set<EntityID> visited = new HashSet<>();
        Queue<EntityID> queue = new LinkedList<>();
        for (EntityID nb : b.getNeighbours()) {
            if (worldInfo.getEntity(nb) instanceof Road && !visited.contains(nb)) {
                queue.add(nb);
                visited.add(nb);
            }
        }
        int depth = MAX_SEARCH_DEPTH;
        boolean foundAny = false;
        while (!queue.isEmpty() && depth-- > 0) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                EntityID roadId = queue.poll();
                Road road = (Road) worldInfo.getEntity(roadId);
                if (road == null) continue;
                boolean blocked = hasBlockades(roadId);
                boolean undefined = !blocked && !isRoadDefined(roadId);
                if (blocked || undefined) {
                    foundAny = true;
                    if (rescueRoutes.add(roadId)) {
                        int prio = blocked ? PRIORITY_RESCUE : PRIORITY_RESCUE_UNDEFINED;
                        rescuePriority.put(roadId, prio);
                    }
                    for (EntityID neighborId : road.getNeighbours()) {
                        if (worldInfo.getEntity(neighborId) instanceof Road && !visited.contains(neighborId)) {
                            visited.add(neighborId);
                            queue.add(neighborId);
                        }
                    }
                }
            }
        }
        if (!foundAny && pathPlanning != null) {
            EntityID nearestRoad = findNearestRoadFromBuilding(building);
            if (nearestRoad != null) {
                rescueRoutes.add(nearestRoad);
                rescuePriority.put(nearestRoad, PRIORITY_FORCED_RESCUE);
            }
        }
    }

    private EntityID findNearestRoadFromBuilding(EntityID buildingId) {
        Collection<EntityID> allRoads = worldInfo.getEntityIDsOfType(ROAD);
        if (allRoads.isEmpty()) return null;
        pathPlanning.setFrom(buildingId);
        pathPlanning.setDestination(allRoads);
        List<EntityID> path = pathPlanning.calc().getResult();
        if (path != null && !path.isEmpty()) {
            for (EntityID step : path) {
                if (worldInfo.getEntity(step) instanceof Road) {
                    return step;
                }
            }
        }
        return null;
    }

    private boolean hasBlockades(EntityID roadId) {
        StandardEntity e = worldInfo.getEntity(roadId);
        if (!(e instanceof Road)) return false;
        Road r = (Road) e;
        return r.isBlockadesDefined() && !r.getBlockades().isEmpty();
    }

    private boolean isRoadDefined(EntityID roadId) {
        StandardEntity e = worldInfo.getEntity(roadId);
        if (!(e instanceof Road)) return false;
        return ((Road) e).isBlockadesDefined();
    }

    private boolean needsClearing(EntityID roadId) {
        StandardEntity e = worldInfo.getEntity(roadId);
        if (!(e instanceof Road)) return false;
        Road r = (Road) e;
        if (r.isBlockadesDefined()) {
            return !r.getBlockades().isEmpty();
        }
        return true; // 未定义也视为需要清理（警察需前往探查）
    }

    private void removeTaskFromAllSources(EntityID roadId) {
        urgentRequests.remove(roadId);
        rescueRoutes.remove(roadId);
        rescuePriority.remove(roadId);
        criticalRoads.remove(roadId);
        taskAssignCount.remove(roadId);
        taskAssignTime.remove(roadId);
        taskQueue.removeIf(t -> t.roadId.equals(roadId));
    }

    private void rebuildTaskQueue() {
        taskQueue.clear();
        for (EntityID r : new HashSet<>(rescueRoutes)) {
            int prio = rescuePriority.getOrDefault(r, PRIORITY_RESCUE);
            if (prio == PRIORITY_FORCED_RESCUE) {
                addTask(r, PRIORITY_FORCED_RESCUE);
            } else {
                if (!needsClearing(r)) {
                    rescueRoutes.remove(r);
                    rescuePriority.remove(r);
                    continue;
                }
                addTask(r, prio);
            }
        }
        for (EntityID r : new HashSet<>(urgentRequests)) {
            if (!needsClearing(r)) {
                urgentRequests.remove(r);
                continue;
            }
            addTask(r, PRIORITY_URGENT);
        }
        for (EntityID r : new HashSet<>(criticalRoads)) {
            if (!needsClearing(r)) {
                criticalRoads.remove(r);
                continue;
            }
            addTask(r, PRIORITY_CRITICAL);
        }
        System.err.printf("[分配器] 时间=%d 队列: 救援=%d 紧急=%d 关键=%d%n",
                currentTime, rescueRoutes.size(), urgentRequests.size(), criticalRoads.size());
    }

    private void addTask(EntityID roadId, int priority) {
        if (taskCooldown.containsKey(roadId)) return;
        int maxPolice = (priority == PRIORITY_FORCED_RESCUE || priority == PRIORITY_RESCUE || priority == PRIORITY_RESCUE_UNDEFINED) 
                        ? MAX_POLICE_PER_RESCUE_TASK : MAX_POLICE_PER_TASK;
        int assigned = taskAssignCount.getOrDefault(roadId, 0);
        if (assigned >= maxPolice) return;
        double minDist = Double.MAX_VALUE;
        for (EntityID policeId : policePositions.keySet()) {
            double d = getDistance(policePositions.get(policeId), roadId);
            if (d < minDist) minDist = d;
        }
        taskQueue.offer(new RoadTask(roadId, priority, minDist));
    }

    private double getDistance(EntityID from, EntityID to) {
        if (pathPlanning != null) return pathPlanning.getDistance(from, to);
        return worldInfo.getDistance(from, to);
    }

    private void assignTasks() {
        allocationResult.clear();
        Set<EntityID> availablePolice = new HashSet<>();
        for (EntityID policeId : policePositions.keySet()) {
            StandardEntity e = worldInfo.getEntity(policeId);
            if (e instanceof PoliceForce) {
                PoliceForce p = (PoliceForce) e;
                if (p.isBuriednessDefined() && p.getBuriedness() > 0) {
                    continue;
                }
            }
            availablePolice.add(policeId);
        }
        if (availablePolice.isEmpty()) return;

        List<RoadTask> tasks = new ArrayList<>(taskQueue);
        tasks.sort(Comparator.comparingInt(t -> t.priority));
        Set<EntityID> assignedThisRound = new HashSet<>();

        // 救援通道
        for (RoadTask task : tasks) {
            boolean isRescueTask = (task.priority == PRIORITY_FORCED_RESCUE || task.priority == PRIORITY_RESCUE || task.priority == PRIORITY_RESCUE_UNDEFINED);
            if (!isRescueTask) continue;
            int maxPolice = MAX_POLICE_PER_RESCUE_TASK;
            if (taskAssignCount.getOrDefault(task.roadId, 0) >= maxPolice) continue;
            EntityID bestPolice = findBestIdlePoliceForTask(task, availablePolice, assignedThisRound);
            if (bestPolice != null) {
                assignTask(bestPolice, task);
                assignedThisRound.add(bestPolice);
            }
        }
        // 紧急开路
        for (RoadTask task : tasks) {
            if (task.priority != PRIORITY_URGENT) continue;
            if (taskAssignCount.getOrDefault(task.roadId, 0) >= MAX_POLICE_PER_TASK) continue;
            EntityID bestPolice = findBestIdlePoliceForTask(task, availablePolice, assignedThisRound);
            if (bestPolice != null) {
                assignTask(bestPolice, task);
                assignedThisRound.add(bestPolice);
            }
        }
        // 关键道路
        for (RoadTask task : tasks) {
            if (task.priority != PRIORITY_CRITICAL) continue;
            if (taskAssignCount.getOrDefault(task.roadId, 0) >= MAX_POLICE_PER_TASK) continue;
            EntityID bestPolice = findBestIdlePoliceForTask(task, availablePolice, assignedThisRound);
            if (bestPolice != null) {
                assignTask(bestPolice, task);
                assignedThisRound.add(bestPolice);
            }
        }

        for (Map.Entry<EntityID, EntityID> entry : policeCurrentTask.entrySet()) {
            if (!allocationResult.containsKey(entry.getKey())) {
                allocationResult.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private EntityID findBestIdlePoliceForTask(RoadTask task, Set<EntityID> availablePolice, Set<EntityID> assignedThisRound) {
        EntityID bestPolice = null;
        double bestDistance = Double.MAX_VALUE;
        for (EntityID policeId : availablePolice) {
            if (assignedThisRound.contains(policeId)) continue;
            if (!policeCurrentTask.containsKey(policeId)) {
                double dist = getDistance(policePositions.get(policeId), task.roadId);
                if (dist < bestDistance) {
                    bestDistance = dist;
                    bestPolice = policeId;
                }
            }
        }
        return bestPolice;
    }

    private void assignTask(EntityID policeId, RoadTask task) {
        allocationResult.put(policeId, task.roadId);
        taskAssignCount.put(task.roadId, taskAssignCount.getOrDefault(task.roadId, 0) + 1);
        taskAssignTime.put(task.roadId, currentTime);
        policeCurrentTask.put(policeId, task.roadId);
        policeTaskStartTime.put(policeId, currentTime);
        policeTaskInfoMap.put(policeId, new PoliceTaskInfo(task.roadId, task.priority, task.distance));
    }

    @Override
    public PoliceTargetAllocator calc() {
        rebuildTaskQueue();
        assignTasks();
        return this;
    }

    @Override
    public Map<EntityID, EntityID> getResult() {
        return allocationResult;
    }

    @Override
    public PoliceTargetAllocator resume(PrecomputeData pd) { return this; }
    @Override
    public PoliceTargetAllocator preparate() { return this; }

    private static class RoadTask {
        EntityID roadId;
        int priority;
        double distance;
        RoadTask(EntityID roadId, int priority, double distance) {
            this.roadId = roadId;
            this.priority = priority;
            this.distance = distance;
        }
    }

    private static class PoliceTaskInfo {
        EntityID taskId;
        int priority;
        double distanceToTask;
        PoliceTaskInfo(EntityID taskId, int priority, double distance) {
            this.taskId = taskId;
            this.priority = priority;
            this.distanceToTask = distance;
        }
    }
}