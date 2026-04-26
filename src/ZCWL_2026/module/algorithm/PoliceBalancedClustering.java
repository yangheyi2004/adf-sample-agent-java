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

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class PoliceBalancedClustering extends Clustering {

    private int clusterSize;
    private List<List<EntityID>> clusterEntityIDsList;
    private Map<EntityID, Integer> roadToClusterMap;
    private Map<EntityID, Integer> policeToClusterMap;
    private Map<Integer, Point2D> clusterCenters;
    
    private List<RoadWithLocation> allRoads;
    private List<PoliceWithLocation> allPolice;
    
    private int repeat;
    private boolean isInitialized;
    
    private Map<Integer, Integer> clusterRoadCount;
    private int minRoadCount = 0;
    private int maxRoadCount = 0;

    public PoliceBalancedClustering(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                    ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.repeat = developData.getInteger("PoliceBalancedClustering.repeat", 10);
        this.clusterSize = si.getScenarioAgentsPf();
        this.clusterEntityIDsList = new ArrayList<>();
        this.roadToClusterMap = new HashMap<>();
        this.policeToClusterMap = new HashMap<>();
        this.clusterCenters = new HashMap<>();
        this.clusterRoadCount = new HashMap<>();
        this.isInitialized = false;
        
        System.err.println("[警察均衡聚类] 初始化，警察数量=" + clusterSize);
    }

    @Override
    public Clustering calc() {
        if (isInitialized) return this;
        
        collectRoads();
        collectPolice();
        
        if (allRoads.isEmpty()) {
            System.err.println("[警察均衡聚类] 未找到任何道路，聚类终止");
            return this;
        }
        
        if (allPolice.isEmpty()) {
            System.err.println("[警察均衡聚类] 未找到任何警察，聚类终止");
            return this;
        }
        
        List<List<RoadWithLocation>> roadClusters = recursiveSplit(allRoads, clusterSize);
        
        computeClusterCenters(roadClusters);
        
        buildRoadMappings(roadClusters);
        
        calculateLoadBalance();
        
        assignPoliceToNearestCluster();
        
        isInitialized = true;
        
        logResult();
        return this;
    }
    
    private void calculateLoadBalance() {
        clusterRoadCount.clear();
        for (int i = 0; i < clusterEntityIDsList.size(); i++) {
            clusterRoadCount.put(i, clusterEntityIDsList.get(i).size());
        }
        
        minRoadCount = clusterRoadCount.values().stream().min(Integer::compareTo).orElse(0);
        maxRoadCount = clusterRoadCount.values().stream().max(Integer::compareTo).orElse(0);
        
        double avgRoadCount = clusterEntityIDsList.stream()
            .mapToInt(List::size)
            .average()
            .orElse(0);
        
        System.err.printf("[警察均衡聚类] 负载均衡: 总道路=%d, 簇数=%d, 平均=%.1f, 最小=%d, 最大=%d%n",
            allRoads.size(), clusterEntityIDsList.size(), avgRoadCount, minRoadCount, maxRoadCount);
        
        for (int i = 0; i < clusterEntityIDsList.size(); i++) {
            System.err.printf("[警察均衡聚类] 簇%d: 道路数=%d%n", 
                i, clusterEntityIDsList.get(i).size());
        }
    }
    
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
        System.err.println("[警察均衡聚类] 收集到 " + allRoads.size() + " 条道路");
    }
    
    private void collectPolice() {
        allPolice = new ArrayList<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(POLICE_FORCE)) {
            if (e instanceof PoliceForce) {
                PoliceForce police = (PoliceForce) e;
                
                if (police.isXDefined() && police.isYDefined()) {
                    allPolice.add(new PoliceWithLocation(police.getID(), police.getX(), police.getY()));
                } 
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
        
        if (allPolice.isEmpty()) {
            for (StandardEntity e : this.worldInfo.getEntitiesOfType(POLICE_FORCE)) {
                allPolice.add(new PoliceWithLocation(e.getID(), 0, 0));
            }
        }
        System.err.println("[警察均衡聚类] 收集到 " + allPolice.size() + " 个警察");
    }
    
    private List<List<RoadWithLocation>> recursiveSplit(List<RoadWithLocation> points, int k) {
        List<List<RoadWithLocation>> result = new ArrayList<>();
        result.add(points);
        
        while (result.size() < k) {
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
    
    private List<List<RoadWithLocation>> kMeansSplit(List<RoadWithLocation> points, int k) {
        if (k <= 1 || points.size() <= 1) {
            List<List<RoadWithLocation>> result = new ArrayList<>();
            result.add(points);
            return result;
        }
        
        Random random = new Random();
        List<RoadWithLocation> centers = new ArrayList<>();
        
        centers.add(points.get(random.nextInt(points.size())));
        RoadWithLocation farthest = findFarthestPoint(points, centers.get(0));
        centers.add(farthest);
        
        List<List<RoadWithLocation>> clusters = new ArrayList<>();
        for (int i = 0; i < k; i++) clusters.add(new ArrayList<>());
        
        boolean changed;
        int iteration = 0;
        do {
            changed = false;
            for (int i = 0; i < k; i++) clusters.get(i).clear();
            
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
    
    private void assignPoliceToNearestCluster() {
        policeToClusterMap.clear();
        
        if (allPolice.isEmpty()) {
            assignPoliceByOrder();
            return;
        }
        
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
        
        assignments.sort(Comparator.comparingDouble(a -> a.distance));
        
        Map<Integer, Integer> clusterAssignCount = new HashMap<>();
        
        Set<Integer> assignedClusters = new HashSet<>();
        for (PoliceAssignment assignment : assignments) {
            if (!assignedClusters.contains(assignment.clusterIndex)) {
                policeToClusterMap.put(assignment.policeId, assignment.clusterIndex);
                clusterAssignCount.put(assignment.clusterIndex, 
                    clusterAssignCount.getOrDefault(assignment.clusterIndex, 0) + 1);
                assignedClusters.add(assignment.clusterIndex);
            }
        }
        
        List<PoliceAssignment> remaining = new ArrayList<>();
        for (PoliceAssignment assignment : assignments) {
            if (!policeToClusterMap.containsKey(assignment.policeId)) {
                remaining.add(assignment);
            }
        }
        
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
        
        System.err.println("[警察均衡聚类] 警察分配结果:");
        for (Map.Entry<EntityID, Integer> entry : policeToClusterMap.entrySet()) {
            int roadCount = clusterEntityIDsList.get(entry.getValue()).size();
            System.err.printf("[警察均衡聚类]   警察 %d -> 簇 %d (道路数=%d)%n", 
                entry.getKey().getValue(), entry.getValue(), roadCount);
        }
        
        int maxPolicePerCluster = clusterAssignCount.values().stream().max(Integer::compareTo).orElse(0);
        int minPolicePerCluster = clusterAssignCount.values().stream().min(Integer::compareTo).orElse(0);
        System.err.printf("[警察均衡聚类] 警察分配均衡: 最小=%d, 最大=%d%n", 
            minPolicePerCluster, maxPolicePerCluster);
    }
    
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
    
    public int getDynamicClusterIndex(EntityID policeId) {
        Integer staticIndex = policeToClusterMap.get(policeId);
        if (staticIndex == null) return -1;
        return staticIndex;
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
        System.err.printf("[警察均衡聚类] 完成聚类，警察数量=%d, 总道路数=%d, 簇道路数范围: %d ~ %d%n",
            clusterSize, allRoads.size(), min, max);
    }
    
    public int getMinRoadCount() { return minRoadCount; }
    public int getMaxRoadCount() { return maxRoadCount; }
    public Map<Integer, Integer> getClusterRoadCount() { return new HashMap<>(clusterRoadCount); }
    public boolean isLoadBalanced() {
        return (maxRoadCount - minRoadCount) <= (allRoads.size() / clusterSize) * 0.5;
    }

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
        if (policeToClusterMap.containsKey(id)) {
            return policeToClusterMap.get(id);
        }
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
