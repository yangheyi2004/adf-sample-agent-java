package ZCWL_2026.module.algorithm;

import adf.core.component.module.algorithm.Clustering;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import java.util.stream.Collectors;

import static rescuecore2.standard.entities.StandardEntityURN.POLICE_FORCE;
import static rescuecore2.standard.entities.StandardEntityURN.ROAD;

/**
 * 警察专用道路均衡聚类 - 增强版
 * 
 * 功能：
 * 1. 将道路划分为与警察数量相等的簇，确保各簇道路数量均衡
 * 2. 考虑警察初始位置，将警察分配到最近的簇
 * 3. 支持动态调整（如果警察移动后远离自己的区域）
 * 
 * 注意：本聚类仅处理道路实体，用于警察任务分配。
 * 建筑搜索通过 MySearch 中基于本簇道路的 BFS 实现，无需在此处包含建筑。
 */
public class PoliceBalancedClustering extends Clustering {

    private int clusterSize;                           // 簇数量（等于警察数量）
    private List<List<EntityID>> clusterEntityIDsList; // 每个簇的道路ID列表
    private Map<EntityID, Integer> roadToClusterMap;   // 道路ID -> 所属簇索引
    private Map<EntityID, Integer> policeToClusterMap; // 警察ID -> 所属簇索引
    private Map<Integer, Point2D> clusterCenters;      // 簇中心点坐标
    
    private List<RoadWithLocation> allRoads;           // 所有道路及其坐标
    private List<PoliceWithLocation> allPolice;        // 所有警察及其初始位置
    
    private int repeat;                                // KMeans迭代次数
    private boolean isInitialized;                     // 是否已初始化

    public PoliceBalancedClustering(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                    ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.repeat = developData.getInteger("PoliceBalancedClustering.repeat", 10);
        this.clusterSize = si.getScenarioAgentsPf();   // 警察数量
        this.clusterEntityIDsList = new ArrayList<>();
        this.roadToClusterMap = new HashMap<>();
        this.policeToClusterMap = new HashMap<>();
        this.clusterCenters = new HashMap<>();
        this.isInitialized = false;
    }

    @Override
    public Clustering calc() {
        if (isInitialized) return this;  // 只计算一次
        
        // 1. 收集所有道路的坐标
        collectRoads();
        
        // 2. 收集所有警察的初始位置
        collectPolice();
        
        if (allRoads.isEmpty()) {
            System.err.println("[警察均衡聚类] 未找到任何道路，聚类终止");
            return this;
        }
        
        if (allPolice.isEmpty()) {
            System.err.println("[警察均衡聚类] 未找到任何警察，聚类终止");
            return this;
        }
        
        // 3. 递归二分，得到 K 个簇（K = 警察数量）
        List<List<RoadWithLocation>> roadClusters = recursiveSplit(allRoads, clusterSize);
        
        // 4. 计算每个簇的中心点
        computeClusterCenters(roadClusters);
        
        // 5. 构建 clusterEntityIDsList 和 roadToClusterMap
        buildRoadMappings(roadClusters);
        
        // 6. 将警察分配到最近的簇（基于初始位置）
        assignPoliceToNearestCluster();
        
        isInitialized = true;
        
        logResult();
        return this;
    }
    
    /**
     * 收集所有道路的坐标
     */
    private void collectRoads() {
        allRoads = new ArrayList<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(ROAD)) {
            if (e instanceof Road) {
                Road road = (Road) e;
                if (road.isXDefined() && road.isYDefined()) {
                    allRoads.add(new RoadWithLocation(road.getID(), road.getX(), road.getY()));
                }
            }
        }
    }
    
    /**
     * 收集所有警察的初始位置
     */
    private void collectPolice() {
        allPolice = new ArrayList<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(POLICE_FORCE)) {
            if (e instanceof PoliceForce) {
                PoliceForce police = (PoliceForce) e;
                
                // 优先使用警察自身的坐标
                if (police.isXDefined() && police.isYDefined()) {
                    allPolice.add(new PoliceWithLocation(police.getID(), police.getX(), police.getY()));
                } 
                // 否则使用位置实体的坐标
                else if (police.isPositionDefined()) {
                    StandardEntity pos = this.worldInfo.getEntity(police.getPosition());
                    if (pos != null) {
                        Pair<Integer, Integer> location = this.worldInfo.getLocation(pos);
                        if (location != null) {
                            allPolice.add(new PoliceWithLocation(police.getID(), location.first(), location.second()));
                        }
                    }
                }
            }
        }
        
        // 如果没有获取到任何警察位置，使用警察ID（兜底方案）
        if (allPolice.isEmpty()) {
            for (StandardEntity e : this.worldInfo.getEntitiesOfType(POLICE_FORCE)) {
                allPolice.add(new PoliceWithLocation(e.getID(), 0, 0));
            }
        }
    }
    
    /**
     * 递归二分，直到得到 k 个簇
     * 确保每个簇的道路数量大致相等
     */
    private List<List<RoadWithLocation>> recursiveSplit(List<RoadWithLocation> points, int k) {
        List<List<RoadWithLocation>> result = new ArrayList<>();
        result.add(points);
        
        while (result.size() < k) {
            // 找到当前最大的簇进行分裂
            int maxIdx = -1;
            int maxSize = -1;
            for (int i = 0; i < result.size(); i++) {
                if (result.get(i).size() > maxSize) {
                    maxSize = result.get(i).size();
                    maxIdx = i;
                }
            }
            if (maxIdx == -1) break;
            
            List<RoadWithLocation> toSplit = result.remove(maxIdx);
            List<List<RoadWithLocation>> split = kMeansSplit(toSplit, 2);
            result.addAll(split);
        }
        
        return result;
    }
    
    /**
     * 使用 KMeans 将点集分为 k 个簇（k=2）
     */
    private List<List<RoadWithLocation>> kMeansSplit(List<RoadWithLocation> points, int k) {
        if (k <= 1 || points.size() <= 1) {
            List<List<RoadWithLocation>> result = new ArrayList<>();
            result.add(points);
            return result;
        }
        
        Random random = new Random();
        List<RoadWithLocation> centers = new ArrayList<>();
        
        // 智能选择初始中心：选择距离最远的两个点
        centers.add(points.get(random.nextInt(points.size())));
        RoadWithLocation farthest = findFarthestPoint(points, centers.get(0));
        centers.add(farthest);
        
        List<List<RoadWithLocation>> clusters = new ArrayList<>();
        for (int i = 0; i < k; i++) clusters.add(new ArrayList<>());
        
        boolean changed;
        int iteration = 0;
        do {
            changed = false;
            // 清空簇
            for (int i = 0; i < k; i++) clusters.get(i).clear();
            
            // 分配点到最近中心
            for (RoadWithLocation p : points) {
                int nearest = 0;
                double minDist = distSq(p, centers.get(0));
                for (int i = 1; i < k; i++) {
                    double d = distSq(p, centers.get(i));
                    if (d < minDist) {
                        minDist = d;
                        nearest = i;
                    }
                }
                clusters.get(nearest).add(p);
            }
            
            // 更新中心
            for (int i = 0; i < k; i++) {
                if (clusters.get(i).isEmpty()) continue;
                double sumX = 0, sumY = 0;
                for (RoadWithLocation p : clusters.get(i)) {
                    sumX += p.x;
                    sumY += p.y;
                }
                double newX = sumX / clusters.get(i).size();
                double newY = sumY / clusters.get(i).size();
                if (Math.abs(centers.get(i).x - newX) > 0.1 || 
                    Math.abs(centers.get(i).y - newY) > 0.1) {
                    centers.get(i).x = newX;
                    centers.get(i).y = newY;
                    changed = true;
                }
            }
            iteration++;
        } while (changed && iteration < repeat);
        
        return clusters;
    }
    
    /**
     * 找到离给定点最远的点
     */
    private RoadWithLocation findFarthestPoint(List<RoadWithLocation> points, RoadWithLocation from) {
        RoadWithLocation farthest = points.get(0);
        double maxDist = 0;
        for (RoadWithLocation p : points) {
            double d = distSq(from, p);
            if (d > maxDist) {
                maxDist = d;
                farthest = p;
            }
        }
        return farthest;
    }
    
    /**
     * 计算每个簇的中心点
     */
    private void computeClusterCenters(List<List<RoadWithLocation>> roadClusters) {
        for (int i = 0; i < roadClusters.size(); i++) {
            List<RoadWithLocation> cluster = roadClusters.get(i);
            if (cluster.isEmpty()) continue;
            
            double sumX = 0, sumY = 0;
            for (RoadWithLocation r : cluster) {
                sumX += r.x;
                sumY += r.y;
            }
            clusterCenters.put(i, new Point2D(sumX / cluster.size(), sumY / cluster.size()));
        }
    }
    
    /**
     * 构建道路到簇的映射
     */
    private void buildRoadMappings(List<List<RoadWithLocation>> roadClusters) {
        clusterEntityIDsList.clear();
        roadToClusterMap.clear();
        
        for (int i = 0; i < roadClusters.size(); i++) {
            List<RoadWithLocation> cluster = roadClusters.get(i);
            List<EntityID> ids = cluster.stream()
                                        .map(r -> r.id)
                                        .collect(Collectors.toList());
            clusterEntityIDsList.add(ids);
            for (RoadWithLocation r : cluster) {
                roadToClusterMap.put(r.id, i);
            }
        }
    }
    
    /**
     * 将警察分配到最近的簇（基于初始位置）
     * 确保每个簇至少分配一个警察
     */
    private void assignPoliceToNearestCluster() {
        policeToClusterMap.clear();
        
        // 如果没有警察位置信息，按警察ID顺序分配
        if (allPolice.isEmpty()) {
            assignPoliceByOrder();
            return;
        }
        
        // 计算每个警察到各簇中心的距离
        List<PoliceAssignment> assignments = new ArrayList<>();
        for (PoliceWithLocation police : allPolice) {
            int nearestCluster = -1;
            double minDist = Double.MAX_VALUE;
            for (Map.Entry<Integer, Point2D> entry : clusterCenters.entrySet()) {
                double dist = police.distanceTo(entry.getValue());
                if (dist < minDist) {
                    minDist = dist;
                    nearestCluster = entry.getKey();
                }
            }
            assignments.add(new PoliceAssignment(police.id, nearestCluster, minDist));
        }
        
        // 按距离排序（距离近的先分配）
        assignments.sort(Comparator.comparingDouble(a -> a.distance));
        
        // 记录每个簇已分配的警察数
        Map<Integer, Integer> clusterAssignCount = new HashMap<>();
        
        // 第一轮：确保每个簇至少有一个警察
        Set<Integer> assignedClusters = new HashSet<>();
        for (PoliceAssignment assignment : assignments) {
            if (!assignedClusters.contains(assignment.clusterIndex)) {
                policeToClusterMap.put(assignment.policeId, assignment.clusterIndex);
                clusterAssignCount.put(assignment.clusterIndex, 
                    clusterAssignCount.getOrDefault(assignment.clusterIndex, 0) + 1);
                assignedClusters.add(assignment.clusterIndex);
            }
        }
        
        // 第二轮：分配剩余的警察（优先分配给警察少的簇）
        List<PoliceAssignment> remaining = new ArrayList<>();
        for (PoliceAssignment assignment : assignments) {
            if (!policeToClusterMap.containsKey(assignment.policeId)) {
                remaining.add(assignment);
            }
        }
        
        // 按距离排序，但优先考虑警察少的簇
        remaining.sort((a, b) -> {
            int countA = clusterAssignCount.getOrDefault(a.clusterIndex, 0);
            int countB = clusterAssignCount.getOrDefault(b.clusterIndex, 0);
            if (countA != countB) {
                return Integer.compare(countA, countB);
            }
            return Double.compare(a.distance, b.distance);
        });
        
        for (PoliceAssignment assignment : remaining) {
            policeToClusterMap.put(assignment.policeId, assignment.clusterIndex);
            clusterAssignCount.put(assignment.clusterIndex,
                clusterAssignCount.getOrDefault(assignment.clusterIndex, 0) + 1);
        }
    }
    
    /**
     * 回退方案：按警察ID顺序分配
     */
    private void assignPoliceByOrder() {
        List<EntityID> policeIds = new ArrayList<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(POLICE_FORCE)) {
            policeIds.add(e.getID());
        }
        policeIds.sort(Comparator.comparingInt(EntityID::getValue));
        
        int numClusters = clusterEntityIDsList.size();
        for (int i = 0; i < policeIds.size(); i++) {
            if (i < numClusters) {
                policeToClusterMap.put(policeIds.get(i), i);
            } else {
                policeToClusterMap.put(policeIds.get(i), numClusters - 1);
            }
        }
    }
    
    /**
     * 获取警察当前应负责的区域（考虑动态调整）
     */
    public int getDynamicClusterIndex(EntityID policeId) {
        Integer staticIndex = policeToClusterMap.get(policeId);
        return staticIndex != null ? staticIndex : -1;
    }
    
    private double distSq(RoadWithLocation a, RoadWithLocation b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return dx * dx + dy * dy;
    }
    
    private void logResult() {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (List<EntityID> ids : clusterEntityIDsList) {
            int s = ids.size();
            if (s < min) min = s;
            if (s > max) max = s;
        }
    }

    // ==================== Clustering 接口实现 ====================
    
    @Override
    public int getClusterNumber() {
        return clusterEntityIDsList.size();
    }

    @Override
    public int getClusterIndex(StandardEntity entity) {
        return getClusterIndex(entity.getID());
    }

    @Override
    public int getClusterIndex(EntityID id) {
        // 优先检查警察映射（用于警察自己查询）
        if (policeToClusterMap.containsKey(id)) {
            return policeToClusterMap.get(id);
        }
        // 再检查道路映射
        Integer idx = roadToClusterMap.get(id);
        return idx != null ? idx : -1;
    }

    @Override
    public Collection<StandardEntity> getClusterEntities(int index) {
        if (index < 0 || index >= clusterEntityIDsList.size()) {
            return Collections.emptyList();
        }
        List<StandardEntity> result = new ArrayList<>();
        for (EntityID id : clusterEntityIDsList.get(index)) {
            StandardEntity entity = this.worldInfo.getEntity(id);
            if (entity != null) {
                result.add(entity);
            }
        }
        return result;
    }

    @Override
    public Collection<EntityID> getClusterEntityIDs(int index) {
        if (index < 0 || index >= clusterEntityIDsList.size()) {
            return Collections.emptyList();
        }
        return clusterEntityIDsList.get(index);
    }

    // ==================== 生命周期方法 ====================
    
    @Override
    public Clustering updateInfo(MessageManager messageManager) {
        return this;
    }

    @Override
    public Clustering precompute(PrecomputeData precomputeData) {
        return this;
    }

    @Override
    public Clustering resume(PrecomputeData precomputeData) {
        return this;
    }

    @Override
    public Clustering preparate() {
        return this;
    }

    // ==================== 内部辅助类 ====================
    
    private static class RoadWithLocation {
        EntityID id;
        double x, y;
        RoadWithLocation(EntityID id, int x, int y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }
    }
    
    private static class PoliceWithLocation {
        EntityID id;
        double x, y;
        PoliceWithLocation(EntityID id, double x, double y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }
        double distanceTo(Point2D p) {
            double dx = x - p.x;
            double dy = y - p.y;
            return Math.hypot(dx, dy);
        }
    }
    
    private static class Point2D {
        double x, y;
        Point2D(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }
    
    private static class PoliceAssignment {
        EntityID policeId;
        int clusterIndex;
        double distance;
        PoliceAssignment(EntityID policeId, int clusterIndex, double distance) {
            this.policeId = policeId;
            this.clusterIndex = clusterIndex;
            this.distance = distance;
        }
    }
}