package ZCWL_2026.module.complex;

import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class PoliceTargetAllocator extends adf.core.component.module.complex.PoliceTargetAllocator {

    // ==================== 优先级常量（重新定义，帮助任务高优先级）====================
    private static final int PRIORITY_HELP_REQUEST = 1;      // 帮助请求（消防员/救护车/被困人员）
    private static final int PRIORITY_REFUGE_PATH = 0;       // 避难所通道 --最高优先级
    private static final int PRIORITY_FIRE_PATH = 2;         // 消防通道
    private static final int PRIORITY_BUILDING_PATH = 3;     // 建筑周边道路
    private static final int PRIORITY_NEAREST = 4;           // 由近及远（最低优先级）

    private static final int MAX_POLICE_PER_TASK = 2;
    private static final int TASK_EXPIRE_TIME = 30;

    // ==================== 数据结构 ====================
    private PathPlanning pathPlanning;
    private Map<EntityID, PoliceInfo> policeMap;
    private PriorityQueue<Task> taskQueue;
    private Map<EntityID, Integer> taskAssignCount;
    
    // 统一的任务集合
    private Set<EntityID> helpRequestTasks;      // 帮助请求（消防员/救护车/被困人员）
    private Set<EntityID> refugePaths;           // 避难所通道
    private Set<EntityID> firePaths;             // 消防通道
    private Set<EntityID> buildingRoads;         // 普通建筑周边道路
    
    // 任务完成跟踪
    private Set<EntityID> completedTasks;
    private Set<EntityID> ignoredTasks;
    private Map<EntityID, Integer> taskStartTime;
    private Set<EntityID> processedTrappedTasks;
    
    private Map<EntityID, List<EntityID>> policeZones;

    public PoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                  ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);

        this.policeMap = new HashMap<>();
        this.taskQueue = new PriorityQueue<>((t1, t2) -> Integer.compare(t1.priority, t2.priority));
        this.taskAssignCount = new HashMap<>();
        
        // 初始化集合
        this.helpRequestTasks = new HashSet<>();
        this.refugePaths = new HashSet<>();
        this.firePaths = new HashSet<>();
        this.buildingRoads = new HashSet<>();
        
        this.completedTasks = new HashSet<>();
        this.ignoredTasks = new HashSet<>();
        this.taskStartTime = new HashMap<>();
        this.processedTrappedTasks = new HashSet<>();
        this.policeZones = new HashMap<>();

        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                    "PoliceTargetAllocator.PathPlanning",
                    "ZCWL_2026.module.algorithm.PathPlanning");
                break;
        }
        if (this.pathPlanning != null) {
            registerModule(this.pathPlanning);
        }
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [ZCWL_2026] 警察分配器已加载                                 ║");
        System.err.println("║  优先级: 帮助请求(0) > 避难所(1) > 消防通道(2) > 建筑(3)     ║");
        System.err.println("║  策略: 空闲 > 打断低优先级任务 > 距离优先                     ║");
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
    }

    @Override
    public PoliceTargetAllocator resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        initialize();
        return this;
    }

    @Override
    public PoliceTargetAllocator preparate() {
        super.preparate();
        if (this.getCountPrecompute() >= 2) return this;
        initialize();
        return this;
    }

    @Override
    public Map<EntityID, EntityID> getResult() {
        Map<EntityID, EntityID> result = new HashMap<>();
        for (Map.Entry<EntityID, PoliceInfo> entry : policeMap.entrySet()) {
            PoliceInfo info = entry.getValue();
            if (info.currentTask != null && !ignoredTasks.contains(info.currentTask)) {
                result.put(entry.getKey(), info.currentTask);
            }
        }
        return result;
    }

    @Override
    public PoliceTargetAllocator calc() {
        updateTaskQueue();
        assignTasks();
        return this;
    }

    @Override
    public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        processMessages(messageManager);
        removeClearedRoads();
        return this;
    }

    // ==================== 初始化 ====================

    private void initialize() {
        for (EntityID id : this.worldInfo.getEntityIDsOfType(POLICE_FORCE)) {
            policeMap.put(id, new PoliceInfo(id));
        }
        initializeRoads();
        divideZones();
    }

    private void initializeRoads() {
        // 避难所周边道路
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE)) {
            addNeighbourRoads(e, refugePaths);
        }
        // 消防站周边道路（消防通道）
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(FIRE_STATION)) {
            addNeighbourRoads(e, firePaths);
        }
        // 普通建筑周边道路
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(BUILDING, GAS_STATION,
                AMBULANCE_CENTRE, POLICE_OFFICE)) {
            addNeighbourRoads(e, buildingRoads);
        }
    }

    private void addNeighbourRoads(StandardEntity building, Set<EntityID> roadSet) {
        if (!(building instanceof Building)) return;
        for (EntityID neighbourId : ((Building) building).getNeighbours()) {
            StandardEntity neighbour = this.worldInfo.getEntity(neighbourId);
            if (neighbour instanceof Road) {
                roadSet.add(neighbourId);
            }
        }
    }

    private void divideZones() {
        List<EntityID> policeIds = new ArrayList<>(policeMap.keySet());
        if (policeIds.isEmpty()) return;
        
        int totalPolice = policeIds.size();
        
        List<RoadWithLocation> allRoadsWithLocation = new ArrayList<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(ROAD)) {
            Road road = (Road) e;
            if (road.isXDefined() && road.isYDefined()) {
                allRoadsWithLocation.add(new RoadWithLocation(road.getID(), road.getX(), road.getY()));
            }
        }
        
        if (allRoadsWithLocation.isEmpty()) return;
        
        List<List<EntityID>> clusters = kMeansClustering(allRoadsWithLocation, totalPolice);
        
        for (int i = 0; i < totalPolice; i++) {
            policeZones.put(policeIds.get(i), clusters.get(i));
            System.err.println("[ZCWL_2026] 警察 " + policeIds.get(i) + " 负责区域道路数: " + clusters.get(i).size());
        }
    }

    private List<List<EntityID>> kMeansClustering(List<RoadWithLocation> roads, int k) {
        if (k <= 0) return new ArrayList<>();
        if (k >= roads.size()) {
            List<List<EntityID>> result = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                result.add(new ArrayList<>());
            }
            for (int i = 0; i < roads.size(); i++) {
                result.get(i % k).add(roads.get(i).id);
            }
            return result;
        }
        
        Random random = new Random();
        List<Point2D> centers = new ArrayList<>();
        List<Integer> selectedIndices = new ArrayList<>();
        while (centers.size() < k) {
            int idx = random.nextInt(roads.size());
            if (!selectedIndices.contains(idx)) {
                selectedIndices.add(idx);
                RoadWithLocation r = roads.get(idx);
                centers.add(new Point2D(r.x, r.y));
            }
        }
        
        List<List<EntityID>> clusters = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            clusters.add(new ArrayList<>());
        }
        
        int maxIterations = 50;
        boolean changed = true;
        int iteration = 0;
        
        while (changed && iteration < maxIterations) {
            changed = false;
            
            for (int i = 0; i < k; i++) {
                clusters.get(i).clear();
            }
            
            for (RoadWithLocation road : roads) {
                int nearestCenter = 0;
                double minDist = distanceSquared(road.x, road.y, centers.get(0).x, centers.get(0).y);
                for (int i = 1; i < k; i++) {
                    double dist = distanceSquared(road.x, road.y, centers.get(i).x, centers.get(i).y);
                    if (dist < minDist) {
                        minDist = dist;
                        nearestCenter = i;
                    }
                }
                clusters.get(nearestCenter).add(road.id);
            }
            
            for (int i = 0; i < k; i++) {
                List<EntityID> cluster = clusters.get(i);
                if (cluster.isEmpty()) continue;
                
                double sumX = 0, sumY = 0;
                for (EntityID roadId : cluster) {
                    for (RoadWithLocation r : roads) {
                        if (r.id.equals(roadId)) {
                            sumX += r.x;
                            sumY += r.y;
                            break;
                        }
                    }
                }
                double newX = sumX / cluster.size();
                double newY = sumY / cluster.size();
                
                if (Math.abs(centers.get(i).x - newX) > 0.1 || Math.abs(centers.get(i).y - newY) > 0.1) {
                    changed = true;
                    centers.set(i, new Point2D(newX, newY));
                }
            }
            iteration++;
        }
        
        return clusters;
    }

    private double distanceSquared(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    private static class RoadWithLocation {
        EntityID id;
        double x;
        double y;
        RoadWithLocation(EntityID id, int x, int y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }
        RoadWithLocation(EntityID id, double x, double y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }
    }

    private static class Point2D {
        double x;
        double y;
        Point2D(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private void processMessages(MessageManager messageManager) {
    for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
        // 消防员开路请求
        handleFiremanMessage(message);
        // 救护车开路请求
        handleAmbulanceMessage(message);
        // 被困人员开路请求
        handleTrappedMessage(message);
        // 其他消息
        handleFireMessage(message);
        handlePoliceMessage(message);
        handleReportMessage(message);
    }
}

    /**
     * 处理消防员消息 - 统一添加到帮助请求任务
     */
    private void handleFiremanMessage(CommunicationMessage message) {
        if (message instanceof MessageFireBrigade) {
            MessageFireBrigade mfb = (MessageFireBrigade) message;
            EntityID target = mfb.getTargetID();
            
            System.err.println("╔══════════════════════════════════════════════════════════════╗");
            System.err.println("║  [警察分配器] 🚨 收到消防员开路请求！                         ║");
            System.err.println("║  消防员 ID: " + mfb.getAgentID());
            System.err.println("║  需要清理的道路: " + target);
            System.err.println("║  优先级: " + PRIORITY_HELP_REQUEST + " (最高优先级)");
            System.err.println("╚══════════════════════════════════════════════════════════════╝");
            
            if (target != null && !ignoredTasks.contains(target)) {
                helpRequestTasks.add(target);
                addTask(target, PRIORITY_HELP_REQUEST);
            }
        }
    }

    /**
 * 处理救护车消息 - 统一添加到帮助请求任务
 */
private void handleAmbulanceMessage(CommunicationMessage message) {
    if (message instanceof MessageAmbulanceTeam) {
        MessageAmbulanceTeam mat = (MessageAmbulanceTeam) message;
        EntityID target = mat.getTargetID();
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [警察分配器] 🚨 收到救护车开路请求！                         ║");
        System.err.println("║  救护车 ID: " + mat.getAgentID());
        System.err.println("║  需要清理的道路: " + target);
        System.err.println("║  优先级: " + PRIORITY_HELP_REQUEST + " (最高优先级)");
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
        
        if (target != null && !ignoredTasks.contains(target)) {
            helpRequestTasks.add(target);
            addTask(target, PRIORITY_HELP_REQUEST);
        }
    }
}

    /**
     * 处理被困人员消息 - 清理建筑门口道路
     */
    private void handleTrappedMessage(CommunicationMessage message) {
        EntityID trappedPosition = null;
        EntityID trappedId = null;
        String type = "";
        
        if (message instanceof MessageCivilian) {
            MessageCivilian msg = (MessageCivilian) message;
            if (msg.isBuriednessDefined() && msg.getBuriedness() > 0 && msg.isPositionDefined()) {
                trappedPosition = msg.getPosition();
                trappedId = msg.getAgentID();
                type = "平民";
            }
        } else if (message instanceof MessageFireBrigade) {
            MessageFireBrigade msg = (MessageFireBrigade) message;
            if (msg.isBuriednessDefined() && msg.getBuriedness() > 0 && msg.isPositionDefined()) {
                trappedPosition = msg.getPosition();
                trappedId = msg.getAgentID();
                type = "消防员";
            }
        } else if (message instanceof MessagePoliceForce) {
            MessagePoliceForce msg = (MessagePoliceForce) message;
            if (msg.isBuriednessDefined() && msg.getBuriedness() > 0 && msg.isPositionDefined()) {
                trappedPosition = msg.getPosition();
                trappedId = msg.getAgentID();
                type = "警察";
            }
        } else if (message instanceof MessageAmbulanceTeam) {
            MessageAmbulanceTeam msg = (MessageAmbulanceTeam) message;
            if (msg.isBuriednessDefined() && msg.getBuriedness() > 0 && msg.isPositionDefined()) {
                trappedPosition = msg.getPosition();
                trappedId = msg.getAgentID();
                type = "救护车";
            }
        }

        if (trappedPosition != null && trappedId != null) {
            if (processedTrappedTasks.contains(trappedId)) {
                return;
            }
            processedTrappedTasks.add(trappedId);
            
            StandardEntity positionEntity = this.worldInfo.getEntity(trappedPosition);
            if (positionEntity instanceof Building) {
                Building building = (Building) positionEntity;
                for (EntityID neighbourId : building.getNeighbours()) {
                    StandardEntity neighbour = this.worldInfo.getEntity(neighbourId);
                    if (neighbour instanceof Road && !ignoredTasks.contains(neighbourId)) {
                        System.err.println("[警察分配器] 🆘 收到" + type + "被困消息，需要清理道路: " + neighbourId);
                        helpRequestTasks.add(neighbourId);
                        addTask(neighbourId, PRIORITY_HELP_REQUEST);
                    }
                }
            } else if (positionEntity instanceof Road && !ignoredTasks.contains(trappedPosition)) {
                System.err.println("[警察分配器] 🆘 收到" + type + "被困消息，需要清理道路: " + trappedPosition);
                helpRequestTasks.add(trappedPosition);
                addTask(trappedPosition, PRIORITY_HELP_REQUEST);
            }
        }
    }

    private void handleFireMessage(CommunicationMessage message) {
        if (message instanceof MessageBuilding) {
            MessageBuilding mb = (MessageBuilding) message;
            if (mb.isFierynessDefined() && mb.getFieryness() > 0) {
                Building building = (Building) this.worldInfo.getEntity(mb.getBuildingID());
                if (building != null) {
                    for (EntityID neighbourId : building.getNeighbours()) {
                        StandardEntity neighbour = this.worldInfo.getEntity(neighbourId);
                        if (neighbour instanceof Road && !ignoredTasks.contains(neighbourId)) {
                            firePaths.add(neighbourId);
                            addTask(neighbourId, PRIORITY_FIRE_PATH);
                        }
                    }
                }
            }
        }
    }

    private void handlePoliceMessage(CommunicationMessage message) {
        if (message instanceof MessagePoliceForce) {
            MessagePoliceForce mpf = (MessagePoliceForce) message;
            PoliceInfo info = policeMap.get(mpf.getAgentID());
            if (info == null) {
                info = new PoliceInfo(mpf.getAgentID());
                policeMap.put(mpf.getAgentID(), info);
            }
            if (mpf.isPositionDefined()) {
                info.currentPosition = mpf.getPosition();
            }
            int currentTime = this.agentInfo.getTime();
            if (currentTime >= info.commandTime + 2) {
                updatePoliceInfo(info, mpf);
            }
        }
    }

    private void updatePoliceInfo(PoliceInfo info, MessagePoliceForce message) {
        if (message.isBuriednessDefined() && message.getBuriedness() > 0) {
            info.isBusy = false;
            info.canNewAction = false;
            if (info.currentTask != null) {
                releaseTask(info.currentTask);
                info.currentTask = null;
            }
            return;
        }

        switch (message.getAction()) {
            case MessagePoliceForce.ACTION_REST:
                info.canNewAction = true;
                info.isBusy = false;
                if (info.currentTask != null) {
                    releaseTask(info.currentTask);
                    info.currentTask = null;
                }
                break;
            case MessagePoliceForce.ACTION_MOVE:
                if (message.getTargetID() != null && message.getTargetID().equals(info.currentTask)) {
                    info.isBusy = true;
                } else {
                    info.canNewAction = true;
                    if (info.currentTask != null) {
                        releaseTask(info.currentTask);
                        info.currentTask = null;
                    }
                }
                break;
            case MessagePoliceForce.ACTION_CLEAR:
                info.isBusy = true;
                info.canNewAction = false;
                break;
        }
    }

    private void handleReportMessage(CommunicationMessage message) {
        if (message instanceof MessageReport) {
            MessageReport report = (MessageReport) message;
            if (report.isDone()) {
                PoliceInfo info = policeMap.get(report.getSenderID());
                if (info != null && info.currentTask != null) {
                    ignoredTasks.add(info.currentTask);
                    completedTasks.add(info.currentTask);
                    taskStartTime.remove(info.currentTask);
                    
                    releaseTask(info.currentTask);
                    info.currentTask = null;
                    info.isBusy = false;
                    info.canNewAction = true;
                    System.err.println("[ZCWL_2026] 警察 " + report.getSenderID() + " 完成任务: " + info.currentTask);
                }
            }
        }
    }

    // ==================== 任务管理 ====================

    private void addTask(EntityID target, int priority) {
        if (ignoredTasks.contains(target)) {
            return;
        }
        for (Task task : taskQueue) {
            if (task.target.equals(target) && task.priority <= priority) {
                return;
            }
        }
        taskQueue.offer(new Task(target, priority, this.agentInfo.getTime()));
        System.err.println("[警察分配器] 📋 添加任务: " + target + " 优先级=" + priority);
    }

    private void updateTaskQueue() {
        int currentTime = this.agentInfo.getTime();

        for (EntityID completed : completedTasks) {
            helpRequestTasks.remove(completed);
            refugePaths.remove(completed);
            firePaths.remove(completed);
            buildingRoads.remove(completed);
            
            List<Task> toRemove = new ArrayList<>();
            for (Task task : taskQueue) {
                if (task.target.equals(completed)) {
                    toRemove.add(task);
                }
            }
            taskQueue.removeAll(toRemove);
        }
        completedTasks.clear();
        
        // 添加任务
        for (EntityID road : helpRequestTasks) addTask(road, PRIORITY_HELP_REQUEST);
        for (EntityID road : refugePaths) addTask(road, PRIORITY_REFUGE_PATH);
        for (EntityID road : firePaths) addTask(road, PRIORITY_FIRE_PATH);
        for (EntityID road : buildingRoads) addTask(road, PRIORITY_BUILDING_PATH);

        List<Task> expired = new ArrayList<>();
        for (Task task : taskQueue) {
            if (currentTime - task.createTime > TASK_EXPIRE_TIME) {
                expired.add(task);
            }
        }
        taskQueue.removeAll(expired);
    }

    /**
 * 为帮助请求任务寻找最佳警察（和救护车一样的逻辑）
 * 策略：优先空闲 -> 无空闲则打断低优先级任务 -> 距离优先
 * 注意：警察不需要检查可达性，因为警察会清理路上的障碍
 */
private EntityID findBestPoliceForHelpRequest(Task task) {
    EntityID bestPolice = null;
    double bestDistance = Double.MAX_VALUE;
    PoliceInfo bestInfo = null;
    
    System.err.println("╔══════════════════════════════════════════════════════════════╗");
    System.err.println("║  [警察分配器] 🔍 为帮助请求寻找警察                           ║");
    System.err.println("║  需要清理的道路: " + task.target);
    System.err.println("║  注意: 警察会清理路上的障碍，所以不检查可达性                  ║");
    System.err.println("╚══════════════════════════════════════════════════════════════╝");
    
    int idleCount = 0;
    int busyCount = 0;
    
    // 第一轮：找空闲的警察（只按距离，不检查可达性）
    for (Map.Entry<EntityID, PoliceInfo> entry : policeMap.entrySet()) {
        EntityID policeId = entry.getKey();
        PoliceInfo info = entry.getValue();
        
        if (info.currentTask == null) {
            idleCount++;
            double distance = getDistance(policeId, task.target);
            System.err.println("[警察分配器] 空闲警察 " + policeId + ", 距离=" + (int)distance);
            
            if (distance < bestDistance) {
                bestDistance = distance;
                bestPolice = policeId;
                bestInfo = info;
            }
        } else {
            busyCount++;
            System.err.println("[警察分配器] 忙碌警察 " + policeId + 
                               ", 当前任务=" + info.currentTask + 
                               ", 优先级=" + info.currentTaskPriority);
        }
    }
    
    System.err.println("[警察分配器] 统计: 空闲=" + idleCount + ", 忙碌=" + busyCount);
    
    if (bestPolice != null) {
        System.err.println("[警察分配器] ✅ 分配空闲警察: " + bestPolice + 
                           " 距离=" + (int)bestDistance);
        return bestPolice;
    }
    
    // 第二轮：没有空闲，找可以打断的（只按距离，不检查可达性）
    System.err.println("[警察分配器] ⚠️ 无空闲警察，尝试打断低优先级任务");
    
    bestDistance = Double.MAX_VALUE;
    int skippedByPriorityCount = 0;
    
    for (Map.Entry<EntityID, PoliceInfo> entry : policeMap.entrySet()) {
        EntityID policeId = entry.getKey();
        PoliceInfo info = entry.getValue();
        
        if (info.currentTask == null) {
            continue;
        }
        
        // 检查是否可以打断（当前任务优先级 > 新任务优先级）
        boolean canInterrupt = (info.currentTaskPriority > PRIORITY_HELP_REQUEST);
        
        System.err.println("[警察分配器] 检查警察 " + policeId + 
                           ": 当前任务优先级=" + info.currentTaskPriority + 
                           ", 可打断=" + canInterrupt);
        
        if (!canInterrupt) {
            skippedByPriorityCount++;
            System.err.println("[警察分配器]   ⏭️ 跳过: 优先级 " + info.currentTaskPriority + 
                               " 不高于 " + PRIORITY_HELP_REQUEST);
            continue;
        }
        
        // 不检查可达性，只按距离
        double distance = getDistance(policeId, task.target);
        System.err.println("[警察分配器]   距离=" + (int)distance);
        
        if (distance < bestDistance) {
            bestDistance = distance;
            bestPolice = policeId;
            bestInfo = info;
        }
    }
    
    System.err.println("[警察分配器] 统计: 忙碌=" + busyCount + 
                       ", 因优先级跳过=" + skippedByPriorityCount);
    
    if (bestPolice != null) {
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [警察分配器] 🔥 打断警察 " + bestPolice + " 的任务！           ║");
        System.err.println("║  原任务: " + bestInfo.currentTask + 
                           " (优先级=" + bestInfo.currentTaskPriority + ")");
        System.err.println("║  新任务: " + task.target + 
                           " (优先级=" + task.priority + ")");
        System.err.println("║  距离: " + (int)bestDistance);
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
        return bestPolice;
    }
    
    System.err.println("[警察分配器] ❌ 无法找到合适的警察执行任务: " + task.target);
    return null;
}

    /**
     * 找最近的空闲警察（用于低优先级任务）
     */
    private EntityID findBestIdlePolice(Task task) {
        EntityID bestPolice = null;
        double bestDistance = Double.MAX_VALUE;
        
        for (Map.Entry<EntityID, PoliceInfo> entry : policeMap.entrySet()) {
            EntityID policeId = entry.getKey();
            PoliceInfo info = entry.getValue();
            
            if (info.currentTask != null) {
                continue;
            }
            
            if (!isReachable(policeId, task.position)) {
                continue;
            }
            
            double distance = getDistance(policeId, task.position);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestPolice = policeId;
            }
        }
        
        return bestPolice;
    }

    /**
     * 分配任务
     */
    private void assignTasks() {
        List<Task> tasks = new ArrayList<>(taskQueue);
        tasks.sort(Comparator.comparingInt(t -> t.priority));
        
        System.err.println("[警察分配器] 开始分配任务，待分配任务数: " + tasks.size());
        for (Task task : tasks) {
            String priorityName = getPriorityName(task.priority);
            System.err.println("[警察分配器]   任务: " + task.target + 
                               ", 优先级=" + task.priority + "(" + priorityName + ")");
        }
        
        Set<EntityID> assignedTasks = new HashSet<>();
        
        for (Task task : tasks) {
            if (assignedTasks.contains(task.target)) continue;
            
            int currentCount = taskAssignCount.getOrDefault(task.target, 0);
            if (currentCount >= MAX_POLICE_PER_TASK) {
                System.err.println("[警察分配器] 任务 " + task.target + " 已满 (" + currentCount + "/" + MAX_POLICE_PER_TASK + ")，跳过");
                continue;
            }
            
            EntityID bestPolice = null;
            
            if (task.priority == PRIORITY_HELP_REQUEST) {
                // 帮助请求：优先空闲，无空闲则打断
                bestPolice = findBestPoliceForHelpRequest(task);
            } else {
                // 其他任务：只分配给空闲警察
                bestPolice = findBestIdlePolice(task);
            }
            
            if (bestPolice != null) {
                assignTaskToPolice(bestPolice, task.target, task.priority);
                assignedTasks.add(task.target);
                taskAssignCount.put(task.target, currentCount + 1);
                taskQueue.remove(task);
                
                String priorityName = getPriorityName(task.priority);
                System.err.println("╔══════════════════════════════════════════════════════════════╗");
                System.err.println("║  [警察分配器] 📍 分配任务！                                   ║");
                System.err.println("║  警察: " + bestPolice);
                System.err.println("║  道路: " + task.target + " (" + priorityName + ")");
                System.err.println("╚══════════════════════════════════════════════════════════════╝");
            }
        }
        
        // 清理已分配的任务
        helpRequestTasks.removeAll(assignedTasks);
        refugePaths.removeAll(assignedTasks);
        firePaths.removeAll(assignedTasks);
        buildingRoads.removeAll(assignedTasks);
    }

    /**
     * 获取任务优先级名称
     */
    private String getPriorityName(int priority) {
        switch (priority) {
            case PRIORITY_HELP_REQUEST: return "帮助请求";
            case PRIORITY_REFUGE_PATH: return "避难所通道";
            case PRIORITY_FIRE_PATH: return "消防通道";
            case PRIORITY_BUILDING_PATH: return "建筑周边道路";
            default: return "普通任务";
        }
    }

    /**
     * 获取任务优先级
     */
    private int getTaskPriority(EntityID task) {
        if (helpRequestTasks.contains(task)) return PRIORITY_HELP_REQUEST;
        if (refugePaths.contains(task)) return PRIORITY_REFUGE_PATH;
        if (firePaths.contains(task)) return PRIORITY_FIRE_PATH;
        if (buildingRoads.contains(task)) return PRIORITY_BUILDING_PATH;
        return PRIORITY_NEAREST;
    }

    private boolean isTaskOverAssigned(EntityID task) {
        return taskAssignCount.getOrDefault(task, 0) >= MAX_POLICE_PER_TASK;
    }

    private void incrementTaskCount(EntityID task) {
        taskAssignCount.put(task, taskAssignCount.getOrDefault(task, 0) + 1);
    }

    private void releaseTask(EntityID task) {
        int count = taskAssignCount.getOrDefault(task, 0);
        if (count > 0) {
            taskAssignCount.put(task, count - 1);
        }
    }

    private void assignTaskToPolice(EntityID policeId, EntityID task, int priority) {
        PoliceInfo info = policeMap.get(policeId);
        if (info != null) {
            if (info.currentTask != null && !info.currentTask.equals(task)) {
                releaseTask(info.currentTask);
            }
            info.currentTask = task;
            info.currentTaskPriority = priority;
            info.isBusy = true;
            info.canNewAction = false;
            info.commandTime = this.agentInfo.getTime();
            
            if (!taskStartTime.containsKey(task)) {
                taskStartTime.put(task, this.agentInfo.getTime());
            }
        }
    }

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

    private boolean isReachable(EntityID policeId, EntityID targetPos) {
        if (this.pathPlanning == null) return true;
        
        StandardEntity agent = this.worldInfo.getEntity(policeId);
        if (!(agent instanceof PoliceForce)) return false;
        PoliceForce police = (PoliceForce) agent;
        if (!police.isPositionDefined()) return false;
        
        List<EntityID> path = this.pathPlanning.getResult(police.getPosition(), targetPos);
        return path != null && path.size() > 0;
    }

    private void removeClearedRoads() {
        for (EntityID id : this.worldInfo.getChanged().getChangedEntities()) {
            StandardEntity entity = this.worldInfo.getEntity(id);
            if (entity instanceof Road) {
                Road road = (Road) entity;
                if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
                    ignoredTasks.add(id);
                    helpRequestTasks.remove(id);
                    refugePaths.remove(id);
                    firePaths.remove(id);
                    buildingRoads.remove(id);
                    taskAssignCount.remove(id);
                    System.err.println("[ZCWL_2026] 道路已清理，忽略: " + id);
                }
            }
        }
    }

    // ==================== 内部类 ====================

    private static class Task {
        EntityID target;
        int priority;
        int createTime;
        EntityID position;
        
        Task(EntityID target, int priority, int createTime) {
            this.target = target;
            this.priority = priority;
            this.createTime = createTime;
            this.position = target;  // 默认位置就是道路ID
        }
    }

    private static class PoliceInfo {   
        EntityID id;
        EntityID currentPosition;
        EntityID currentTask;
        int currentTaskPriority;
        boolean isBusy;
        boolean canNewAction;
        int commandTime;
        
        PoliceInfo(EntityID id) {
            this.id = id;
            this.currentTask = null;
            this.currentTaskPriority = PRIORITY_NEAREST;
            this.isBusy = false;
            this.canNewAction = true;
            this.commandTime = -1;
        }
    }
}