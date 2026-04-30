package ZCWL_2026.module.algorithm;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import rescuecore2.misc.collections.LazyMap;
import rescuecore2.standard.entities.Area;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import java.util.stream.Collectors;

public class PathPlanning extends adf.core.component.module.algorithm.PathPlanning {

    private Map<EntityID, Set<EntityID>> graph;

    private EntityID from;
    private Collection<EntityID> targets;
    private List<EntityID> result;

    // ========== BFS 深度限制 ==========
    private static final int MAX_SEARCH_DEPTH = 4000;

    // ========== 缓存相关字段 ==========
    private static class CacheKey {
        final EntityID from;
        final List<EntityID> sortedTargets;
        final int timeStep;

        CacheKey(EntityID from, Collection<EntityID> targets, int timeStep) {
            this.from = from;
            this.sortedTargets = targets == null ? Collections.emptyList() 
                    : targets.stream()
                             .sorted(Comparator.comparingInt(EntityID::getValue))
                             .collect(Collectors.toList());
            this.timeStep = timeStep;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey cacheKey = (CacheKey) o;
            return timeStep == cacheKey.timeStep &&
                    Objects.equals(from, cacheKey.from) &&
                    Objects.equals(sortedTargets, cacheKey.sortedTargets);
        }

        @Override
        public int hashCode() {
            return Objects.hash(from, sortedTargets, timeStep);
        }
    }

    private CacheKey lastCacheKey = null;
    private List<EntityID> cachedResult = null;

    public PathPlanning(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.init();
    }

    private void init() {
        Map<EntityID, Set<EntityID>> neighbours = new LazyMap<EntityID, Set<EntityID>>() {
            @Override
            public Set<EntityID> createValue() {
                return new HashSet<>();
            }
        };
        for (Entity next : this.worldInfo) {
            if (next instanceof Area) {
                Collection<EntityID> areaNeighbours = ((Area) next).getNeighbours();
                neighbours.get(next.getID()).addAll(areaNeighbours);
            }
        }
        this.graph = neighbours;
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

    @Override
    public PathPlanning updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        return this;
    }

    @Override
    public PathPlanning precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        return this;
    }

    @Override
    public PathPlanning resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        return this;
    }

    @Override
    public PathPlanning preparate() {
        super.preparate();
        return this;
    }

    @Override
    public PathPlanning calc() {
        // 缓存检查
        int currentTime = this.agentInfo.getTime();
        CacheKey currentKey = new CacheKey(this.from, this.targets, currentTime);
        if (currentKey.equals(lastCacheKey) && cachedResult != null) {
            this.result = cachedResult;
            return this;
        }

        // 使用队列进行 BFS，同时记录每个节点的深度
        Queue<EntityID> open = new LinkedList<>();
        Map<EntityID, EntityID> ancestors = new HashMap<>();
        Map<EntityID, Integer> depth = new HashMap<>();   // 记录深度
        
        open.add(this.from);
        ancestors.put(this.from, this.from);
        depth.put(this.from, 0);
        
        EntityID next = null;
        boolean found = false;
        int currentDepth = 0;
        
        while (!open.isEmpty() && !found) {
            next = open.poll();
            currentDepth = depth.get(next);
            
            // 深度限制检查
            if (currentDepth > MAX_SEARCH_DEPTH) {
                System.err.println("[PathPlanning] BFS 超过最大深度限制 " + MAX_SEARCH_DEPTH + "，终止搜索。from=" + this.from + " targets=" + this.targets);
                this.result = null;
                // 不缓存这次失败结果，因为可能由于深度限制导致未找到路径，下次可能状态变化
                return this;
            }
            
            if (isGoal(next, targets)) {
                found = true;
                break;
            }
            
            Collection<EntityID> neighbours = graph.get(next);
            if (neighbours.isEmpty()) {
                continue;
            }
            
            for (EntityID neighbour : neighbours) {
                // 如果邻居是目标，立即完成
                if (isGoal(neighbour, targets)) {
                    ancestors.put(neighbour, next);
                    depth.put(neighbour, currentDepth + 1);
                    next = neighbour;
                    found = true;
                    break;
                }
                
                if (!ancestors.containsKey(neighbour)) {
                    open.add(neighbour);
                    ancestors.put(neighbour, next);
                    depth.put(neighbour, currentDepth + 1);
                }
            }
        }
        
        if (!found) {
            // 无路径
            this.result = null;
        } else {
            // 回溯构建路径
            EntityID current = next;
            LinkedList<EntityID> path = new LinkedList<>();
            do {
                path.add(0, current);
                current = ancestors.get(current);
                if (current == null) {
                    throw new RuntimeException("Found a node with no ancestor! Something is broken.");
                }
            } while (current != this.from);
            this.result = path;
        }

        // 保存缓存
        lastCacheKey = currentKey;
        cachedResult = this.result == null ? null : new ArrayList<>(this.result);
        return this;
    }

    private boolean isGoal(EntityID e, Collection<EntityID> test) {
        return test.contains(e);
    }
}