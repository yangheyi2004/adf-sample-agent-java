package ZCWL_2026.module.algorithm;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import rescuecore2.misc.Pair;
import rescuecore2.misc.collections.LazyMap;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class PathPlanning extends adf.core.component.module.algorithm.PathPlanning {

    // ========== 使用 WeakHashMap 避免内存泄漏 ==========
    private static Map<WorldInfo, Map<EntityID, Set<EntityID>>> graphCache = new WeakHashMap<>();
    
    // ========== 缓存大小限制 ==========
    private static final int MAX_CACHE_SIZE = 5000;
    private static final int MAX_SEARCH_DEPTH = 3000;
    
    private Map<EntityID, Set<EntityID>> graph;
    private Map<String, List<EntityID>> pathCache = new LinkedHashMap<String, List<EntityID>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<EntityID>> eldest) {
            return size() > MAX_CACHE_SIZE;  // 超过限制时移除最旧的条目
        }
    };

    private EntityID from;
    private Collection<EntityID> targets;
    private List<EntityID> result;

    public PathPlanning(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                        ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        initGraph();
    }

    private synchronized void initGraph() {
        // WeakHashMap 会自动清理不再被引用的 WorldInfo
        if (graphCache.containsKey(this.worldInfo)) {
            this.graph = graphCache.get(this.worldInfo);
            return;
        }
        
        Map<EntityID, Set<EntityID>> neighbours = new LazyMap<EntityID, Set<EntityID>>() {
            @Override
            public Set<EntityID> createValue() {
                return new HashSet<>();
            }
        };
        
        for (Entity next : this.worldInfo) {
            if (next instanceof Area) {
                neighbours.get(next.getID()).addAll(((Area) next).getNeighbours());
            }
        }
        
        this.graph = neighbours;
        graphCache.put(this.worldInfo, this.graph);
    }

    @Override
    public List<EntityID> getResult() {
        return this.result;
    }

    @Override
    public PathPlanning setFrom(EntityID id) {
        this.from = id;
        return this;
    }

    @Override
    public PathPlanning setDestination(Collection<EntityID> targets) {
        this.targets = targets;
        return this;
    }

    /**
     * 生成稳定的缓存键
     */
    private String generateCacheKey() {
        if (from == null || targets == null) return null;
        
        StringBuilder sb = new StringBuilder();
        sb.append(from.getValue()).append("->");
        
        // 对目标进行排序，确保相同目标集合产生相同键
        List<Integer> sortedTargets = new ArrayList<>();
        for (EntityID id : targets) {
            sortedTargets.add(id.getValue());
        }
        Collections.sort(sortedTargets);
        
        for (int i = 0; i < sortedTargets.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(sortedTargets.get(i));
        }
        
        return sb.toString();
    }

    @Override
    public PathPlanning calc() {
        if (from == null || targets == null || targets.isEmpty()) {
            result = null;
            return this;
        }
        
        String key = generateCacheKey();
        if (key != null && pathCache.containsKey(key)) {
            result = pathCache.get(key);
            return this;
        }

        // ========== A* 算法 ==========
        Map<EntityID, Double> gScore = new HashMap<>();
        Map<EntityID, Double> fScore = new HashMap<>();
        Map<EntityID, EntityID> cameFrom = new HashMap<>();
        PriorityQueue<EntityID> openSet = new PriorityQueue<>(Comparator.comparingDouble(fScore::get));

        gScore.put(from, 0.0);
        fScore.put(from, heuristic(from));
        openSet.add(from);

        EntityID current = null;
        boolean found = false;
        int searchDepth = 0;

        while (!openSet.isEmpty() && searchDepth < MAX_SEARCH_DEPTH) {
            current = openSet.poll();
            if (isGoal(current, targets)) {
                found = true;
                break;
            }
            
            Set<EntityID> neighbours = graph.get(current);
            if (neighbours != null) {
                for (EntityID neighbor : neighbours) {
                    double tentativeG = gScore.get(current) + distance(current, neighbor);
                    if (tentativeG < gScore.getOrDefault(neighbor, Double.MAX_VALUE)) {
                        cameFrom.put(neighbor, current);
                        gScore.put(neighbor, tentativeG);
                        double h = heuristic(neighbor);
                        fScore.put(neighbor, tentativeG + h);
                        if (!openSet.contains(neighbor)) openSet.add(neighbor);
                    }
                }
            }
            searchDepth++;
        }

        if (!found) {
            // 搜索超限或未找到路径
            if (searchDepth >= MAX_SEARCH_DEPTH) {
                System.err.println("[PathPlanning] 搜索深度超限 (" + MAX_SEARCH_DEPTH + ")，放弃计算");
            }
            result = null;
            return this;
        }

        List<EntityID> path = new LinkedList<>();
        EntityID node = current;
        while (node != null && !node.equals(from)) {
            path.add(0, node);
            node = cameFrom.get(node);
        }
        path.add(0, from);
        result = path;
        
        // 只有有效路径才缓存
        if (key != null && result != null && !result.isEmpty()) {
            pathCache.put(key, result);
        }
        
        return this;
    }

    private double heuristic(EntityID id) {
        if (targets == null || targets.isEmpty()) return 0;
        StandardEntity entity = this.worldInfo.getEntity(id);
        if (!(entity instanceof Area)) return 0;
        Pair<Integer, Integer> loc = this.worldInfo.getLocation((Area) entity);
        double minDist = Double.MAX_VALUE;
        for (EntityID target : targets) {
            StandardEntity t = this.worldInfo.getEntity(target);
            if (t instanceof Area) {
                Pair<Integer, Integer> tLoc = this.worldInfo.getLocation((Area) t);
                double dx = loc.first() - tLoc.first();
                double dy = loc.second() - tLoc.second();
                minDist = Math.min(minDist, Math.hypot(dx, dy));
            }
        }
        return minDist;
    }

    private double distance(EntityID a, EntityID b) {
        StandardEntity ea = this.worldInfo.getEntity(a);
        StandardEntity eb = this.worldInfo.getEntity(b);
        if (ea instanceof Area && eb instanceof Area) {
            return this.worldInfo.getDistance((Area) ea, (Area) eb);
        }
        return 1.0;
    }

    private boolean isGoal(EntityID e, Collection<EntityID> test) {
        return test.contains(e);
    }

    /**
     * 可选：清理缓存的方法（在模拟结束时调用）
     */
    public void clearCache() {
        pathCache.clear();
    }
    
    /**
     * 获取当前缓存大小（用于调试）
     */
    public int getCacheSize() {
        return pathCache.size();
    }

    @Override
    public PathPlanning updateInfo(MessageManager messageManager) {
        return this;
    }

    @Override
    public PathPlanning precompute(PrecomputeData precomputeData) {
        return this;
    }

    @Override
    public PathPlanning resume(PrecomputeData precomputeData) {
        return this;
    }

    @Override
    public PathPlanning preparate() {
        return this;
    }
}