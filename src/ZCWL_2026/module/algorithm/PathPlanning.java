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

public class PathPlanning extends adf.core.component.module.algorithm.PathPlanning {

    // 图结构：存储每个节点的邻居
    private Map<EntityID, Set<EntityID>> graph;
    
    // 路径规划参数
    private EntityID from;                      // 起点
    private Collection<EntityID> targets;      // 目标点集合
    private List<EntityID> result;             // 计算结果（路径）

    public PathPlanning(AgentInfo ai, WorldInfo wi, ScenarioInfo si, 
                        ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        initGraph();  // 初始化图结构
    }

    /**
     * 初始化图结构：构建所有Area之间的连接关系
     */
    private void initGraph() {
        Map<EntityID, Set<EntityID>> neighbours = new LazyMap<EntityID, Set<EntityID>>() {
            @Override
            public Set<EntityID> createValue() {
                return new HashSet<>();
            }
        };
        
        // 遍历所有实体，构建邻居关系
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

    /**
     * BFS算法计算最短路径
     */
    @Override
    public PathPlanning calc() {
        // 检查参数有效性
        if (this.from == null || this.targets == null || this.targets.isEmpty()) {
            this.result = null;
            return this;
        }
        
        // BFS搜索
        List<EntityID> open = new LinkedList<>();           // 待搜索队列
        Map<EntityID, EntityID> ancestors = new HashMap<>(); // 记录前驱节点
        open.add(this.from);
        EntityID next;
        boolean found = false;
        ancestors.put(this.from, this.from);
        
        do {
            next = open.remove(0);  // 队列头节点
            
            // 检查当前节点是否为目标
            if (isGoal(next, targets)) {
                found = true;
                break;
            }
            
            // 获取邻居节点
            Collection<EntityID> neighbours = graph.get(next);
            if (neighbours == null || neighbours.isEmpty()) {
                continue;
            }
            
            // 遍历邻居
            for (EntityID neighbour : neighbours) {
                if (isGoal(neighbour, targets)) {
                    ancestors.put(neighbour, next);
                    next = neighbour;
                    found = true;
                    break;
                } else {
                    if (!ancestors.containsKey(neighbour)) {
                        open.add(neighbour);
                        ancestors.put(neighbour, next);
                    }
                }
            }
        } while (!found && !open.isEmpty());
        
        // 未找到路径
        if (!found) {
            this.result = null;
            return this;
        }
        
        // 构建路径（从目标回溯到起点）
        EntityID current = next;
        LinkedList<EntityID> path = new LinkedList<>();
        do {
            path.add(0, current);
            current = ancestors.get(current);
            if (current == null) {
                throw new RuntimeException("Found a node with no ancestor! Something is broken.");
            }
        } while (!current.equals(this.from));
        
        this.result = path;
        return this;
    }

    /**
     * 判断节点是否为目标节点
     */
    private boolean isGoal(EntityID e, Collection<EntityID> test) {
        return test.contains(e);
    }
}