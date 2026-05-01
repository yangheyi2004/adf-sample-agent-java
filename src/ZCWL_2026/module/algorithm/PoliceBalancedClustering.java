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
    }

    @Override
    public Clustering calc() {
        if (isInitialized) return this;
        
        collectRoads();
        collectPolice();
        
        if (allRoads.isEmpty()) {
            return this;
        }
        
        if (allPolice.isEmpty()) {
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
        allRoads.sort(Comparator.comparingInt(r -> r.id.getValue()));
    }
    
    private void collectPolice() {
        allPolice = new ArrayList<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(POLICE_FORCE)) {
            if (e instanceof PoliceForce) {
                PoliceForce police = (PoliceForce) e;
                // 保留所有警察，包括可能被掩埋的，以确保每个警察都有集群索引
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
        // 确保所有警察都被添加，即使没有坐标
        if (allPolice.size() < this.worldInfo.getEntitiesOfType(POLICE_FORCE).size()) {
            Set<EntityID> existing = allPolice.stream().map(p -> p.id).collect(Collectors.toSet());
            for (StandardEntity e : this.worldInfo.getEntitiesOfType(POLICE_FORCE)) {
                if (!existing.contains(e.getID())) {
                    allPolice.add(new PoliceWithLocation(e.getID(), 0, 0));
                }
            }
        }
        allPolice.sort(Comparator.comparingInt(p -> p.id.getValue()));
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
        
        Random random = new Random(getFixedSeed());
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
    
    private long getFixedSeed() {
        return Objects.hash(worldInfo.getBounds().getX(), worldInfo.getBounds().getY(),
                            worldInfo.getBounds().getWidth(), worldInfo.getBounds().getHeight(), 0x50D1CE);
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

        List<PoliceAssignment> allCombinations = new ArrayList<>();
        for (PoliceWithLocation police : allPolice) {
            for (Map.Entry<Integer, Point2D> entry : clusterCenters.entrySet()) {
                int clusterIdx = entry.getKey();
                double dist = police.distanceTo(entry.getValue());
                allCombinations.add(new PoliceAssignment(police.id, clusterIdx, dist));
            }
        }
        allCombinations.sort(Comparator.comparingDouble(a -> a.distance));

        Set<EntityID> assignedPolice = new HashSet<>();
        Set<Integer> assignedClusters = new HashSet<>();

        for (PoliceAssignment assign : allCombinations) {
            if (assignedPolice.contains(assign.policeId)) continue;
            if (assignedClusters.contains(assign.clusterIndex)) continue;

            policeToClusterMap.put(assign.policeId, assign.clusterIndex);
            assignedPolice.add(assign.policeId);
            assignedClusters.add(assign.clusterIndex);

            if (assignedPolice.size() == allPolice.size()) break;
        }

        if (assignedPolice.size() < allPolice.size()) {
            List<EntityID> unassigned = allPolice.stream()
                    .map(p -> p.id)
                    .filter(id -> !policeToClusterMap.containsKey(id))
                    .collect(Collectors.toList());
            List<Integer> emptyClusters = new ArrayList<>();
            for (int i = 0; i < clusterEntityIDsList.size(); i++) {
                if (!assignedClusters.contains(i)) emptyClusters.add(i);
            }
            for (int i = 0; i < unassigned.size() && i < emptyClusters.size(); i++) {
                policeToClusterMap.put(unassigned.get(i), emptyClusters.get(i));
            }
        }
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
        //System.err.println("[警察均衡聚类] 分配完成，每个集群的警察数量：");
        Map<Integer, Integer> countMap = new HashMap<>();
        for (EntityID pid : policeToClusterMap.keySet()) {
            int cid = policeToClusterMap.get(pid);
            countMap.put(cid, countMap.getOrDefault(cid, 0) + 1);
        }
        for (int i = 0; i < clusterEntityIDsList.size(); i++) {
            int c = countMap.getOrDefault(i, 0);
            //System.err.printf("  集群 %d : %d 名警察%s%n", i, c, c > 1 ? " ★ 重复！" : "");
        }
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