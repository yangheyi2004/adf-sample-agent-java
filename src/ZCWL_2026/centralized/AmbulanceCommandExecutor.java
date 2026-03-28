package ZCWL_2026.centralized;

import adf.core.component.communication.CommunicationMessage;
import adf.core.component.centralized.CommandExecutor;
import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionLoad;
import adf.core.agent.action.ambulance.ActionRescue;
import adf.core.agent.action.ambulance.ActionUnload;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandAmbulance;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

public class AmbulanceCommandExecutor extends CommandExecutor {

  private static final int ACTION_UNKNOWN  = -1;
  private static final int ACTION_REST     = CommandAmbulance.ACTION_REST;
  private static final int ACTION_MOVE     = CommandAmbulance.ACTION_MOVE;
  private static final int ACTION_RESCUE   = CommandAmbulance.ACTION_RESCUE;
  private static final int ACTION_LOAD     = CommandAmbulance.ACTION_LOAD;
  private static final int ACTION_UNLOAD   = CommandAmbulance.ACTION_UNLOAD;
  private static final int ACTION_AUTONOMY = CommandAmbulance.ACTION_AUTONOMY;

  private PathPlanning     pathPlanning;
  private ExtAction        actionTransport;
  private ExtAction        actionExtMove;

  private int              commandType;
  private EntityID         target;
  private EntityID         commanderID;
  private boolean          reportSent;
  private boolean          isForcedLoad;    // 是否是强制装载任务
  private int              taskPriority;    // 任务优先级

  public AmbulanceCommandExecutor( AgentInfo ai, WorldInfo wi, ScenarioInfo si, 
                                   ModuleManager moduleManager, DevelopData developData ) {
    super( ai, wi, si, moduleManager, developData );
    this.commandType = ACTION_UNKNOWN;
    this.reportSent = false;
    this.isForcedLoad = false;
    this.taskPriority = 2;  // 默认搜索优先级
    
    switch ( scenarioInfo.getMode() ) {
      case PRECOMPUTATION_PHASE:
      case PRECOMPUTED:
      case NON_PRECOMPUTE:
        this.pathPlanning = moduleManager.getModule(
            "AmbulanceCommandExecutor.PathPlanning",
            "ZCWL_2026.module.algorithm.PathPlanning" );
        this.actionTransport = moduleManager.getExtAction(
            "AmbulanceCommandExecutor.ActionTransport",
            "ZCWL_2026.extraction.ActionTransport" );
        this.actionExtMove = moduleManager.getExtAction(
            "AmbulanceCommandExecutor.ActionExtMove",
            "ZCWL_2026.extraction.ActionExtMove" );
        break;
    }
  }

  @Override
  public CommandExecutor setCommand(CommunicationMessage command) {
    if (command instanceof CommandAmbulance) {
        CommandAmbulance cmd = (CommandAmbulance) command;
        EntityID agentID = this.agentInfo.getID();
        if (cmd.isToIDDefined() && Objects.requireNonNull(cmd.getToID())
                .getValue() == agentID.getValue()) {
            this.commandType = cmd.getAction();
            this.target = cmd.getTargetID();
            this.commanderID = cmd.getSenderID();
            this.reportSent = false;
            
            // 判断任务优先级
            this.isForcedLoad = (cmd.getAction() == ACTION_LOAD);
            if (this.isForcedLoad) {
                this.taskPriority = 0;  // 强制装载最高优先级
            } else {
                this.taskPriority = 2;  // 搜索任务
            }
            
            String actionName = "";
            if (this.commandType == ACTION_REST) actionName = "休息";
            else if (this.commandType == ACTION_MOVE) actionName = "移动";
            else if (this.commandType == ACTION_RESCUE) actionName = "救援";
            else if (this.commandType == ACTION_LOAD) actionName = "装载";
            else if (this.commandType == ACTION_UNLOAD) actionName = "卸载";
            else if (this.commandType == ACTION_AUTONOMY) actionName = "自主";
            
            if (this.isForcedLoad) {
                System.err.println("╔══════════════════════════════════════════════════════════════╗");
                System.err.println("║  [救护车] ID:" + agentID + " 🚨 收到强制装载命令！（最高优先级）");
                System.err.println("║  目标: " + this.target + "                                         ");
                System.err.println("║  发送者: " + this.commanderID + "                                  ");
                System.err.println("╚══════════════════════════════════════════════════════════════╝");
            } else {
                System.err.println("[救护车] ID:" + agentID + " 收到命令: " + actionName + " 目标=" + this.target);
            }
        }
    }
    return this;
  }

  @Override
  public CommandExecutor updateInfo( MessageManager messageManager ) {
    super.updateInfo( messageManager );
    if ( this.getCountUpdateInfo() >= 2 ) return this;
    
    this.pathPlanning.updateInfo( messageManager );
    this.actionTransport.updateInfo( messageManager );
    this.actionExtMove.updateInfo( messageManager );

    // 检查任务是否完成
    if ( !reportSent && this.commandType != ACTION_UNKNOWN && this.target != null ) {
      if ( isCommandCompleted() ) {
        messageManager.addMessage( new MessageReport( true, true, false, this.commanderID ) );
        reportSent = true;
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [救护车] ID:" + this.agentInfo.getID() + " ✅ 完成任务！              ");
        System.err.println("║  目标: " + this.target + "                                         ");
        System.err.println("║  任务类型: " + (this.commandType == ACTION_LOAD ? "装载" : 
                              (this.commandType == ACTION_UNLOAD ? "卸载" : "其他")));
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
        
        // 装载完成后，转为卸载模式
        if ( this.commandType == ACTION_LOAD ) {
          this.commandType = ACTION_UNLOAD;
          this.target = null;
          this.isForcedLoad = false;
          System.err.println("[救护车] ID:" + this.agentInfo.getID() + " 🔄 装载完成，转为卸载模式");
        } else {
          this.commandType = ACTION_UNKNOWN;
          this.target = null;
          this.commanderID = null;
          this.isForcedLoad = false;
        }
      }
    }
    return this;
  }

  @Override
  public CommandExecutor precompute( PrecomputeData precomputeData ) {
    super.precompute( precomputeData );
    if ( this.getCountPrecompute() >= 2 ) return this;
    this.pathPlanning.precompute( precomputeData );
    this.actionTransport.precompute( precomputeData );
    this.actionExtMove.precompute( precomputeData );
    return this;
  }

  @Override
  public CommandExecutor resume( PrecomputeData precomputeData ) {
    super.resume( precomputeData );
    if ( this.getCountResume() >= 2 ) return this;
    this.pathPlanning.resume( precomputeData );
    this.actionTransport.resume( precomputeData );
    this.actionExtMove.resume( precomputeData );
    return this;
  }

  @Override
  public CommandExecutor preparate() {
    super.preparate();
    if ( this.getCountPreparate() >= 2 ) return this;
    this.pathPlanning.preparate();
    this.actionTransport.preparate();
    this.actionExtMove.preparate();
    return this;
  }

  @Override
  public CommandExecutor calc() {
    this.result = null;
    EntityID agentID = this.agentInfo.getID();
    
    // 检查是否有伤员在车上，优先处理运输
    if (this.agentInfo.someoneOnBoard() != null) {
      Human passenger = this.agentInfo.someoneOnBoard();
      System.err.println("[救护车] ID:" + agentID + " 🚑 车上有伤员 ID:" + passenger.getID() + 
                         "，优先执行运输任务");
      this.actionTransport.setTarget((EntityID) null);
      Action transportAction = this.actionTransport.calc().getAction();
      if (transportAction != null) {
        this.result = transportAction;
        if (transportAction instanceof ActionMove) {
          List<EntityID> path = ((ActionMove) transportAction).getPath();
          System.err.println("[救护车] ID:" + agentID + " 📍 运输伤员前往避难所，目标=" + 
                             (path.isEmpty() ? "未知" : path.get(path.size()-1)));
        } else if (transportAction instanceof ActionRest) {
          System.err.println("[救护车] ID:" + agentID + " 🏥 已在避难所，准备卸载");
        }
        return this;
      }
    }
    
    switch ( this.commandType ) {
      case ACTION_REST:
        System.err.println("[救护车] ID:" + agentID + " 🛌 执行休息动作");
        this.result = handleRest();
        if (this.result instanceof ActionRest) {
          System.err.println("[救护车] ID:" + agentID + " ✅ 已在避难所，开始休息");
        } else if (this.result instanceof ActionMove) {
          System.err.println("[救护车] ID:" + agentID + " 📍 前往避难所休息");
        }
        break;
        
      case ACTION_MOVE:
        if ( this.target != null ) {
          System.err.println("[救护车] ID:" + agentID + " 🚶 执行移动命令，目标=" + this.target);
          this.actionExtMove.setTarget(this.target);
          this.result = this.actionExtMove.calc().getAction();
          if (this.result instanceof ActionMove) {
            List<EntityID> path = ((ActionMove) this.result).getPath();
            System.err.println("[救护车] ID:" + agentID + " 📍 移动到: " + this.target + 
                               " 路径长度=" + (path == null ? 0 : path.size()));
          }
        }
        break;
        
      case ACTION_RESCUE:
        if ( this.target != null ) {
          System.err.println("[救护车] ID:" + agentID + " 🆘 执行救援命令，目标=" + this.target);
          this.actionTransport.setTarget(this.target);
          this.result = this.actionTransport.calc().getAction();
          if (this.result instanceof ActionRescue) {
            System.err.println("[救护车] ID:" + agentID + " 🏥 执行救援动作");
          }
        }
        break;
        
      case ACTION_LOAD:
        if ( this.target != null ) {
          String loadType = this.isForcedLoad ? "强制装载" : "普通装载";
          System.err.println("╔══════════════════════════════════════════════════════════════╗");
          System.err.println("║  [救护车] ID:" + agentID + " 📦 执行" + loadType + "命令              ");
          System.err.println("║  目标: " + this.target + "                                         ");
          System.err.println("╚══════════════════════════════════════════════════════════════╝");
          this.actionTransport.setTarget(this.target);
          this.result = this.actionTransport.calc().getAction();
          if (this.result instanceof ActionLoad) {
            System.err.println("[救护车] ID:" + agentID + " ✅ 执行装载动作");
          } else if (this.result == null) {
            System.err.println("[救护车] ID:" + agentID + " ⚠️ 装载动作返回null，可能平民状态不符");
          }
        }
        break;
        
      case ACTION_UNLOAD:
        System.err.println("[救护车] ID:" + agentID + " 🏥 执行卸载命令");
        this.actionTransport.setTarget((EntityID) null);
        this.result = this.actionTransport.calc().getAction();
        if (this.result instanceof ActionUnload) {
          System.err.println("[救护车] ID:" + agentID + " ✅ 执行卸载动作");
        }
        break;
        
      case ACTION_AUTONOMY:
        if ( this.target != null ) {
          System.err.println("[救护车] ID:" + agentID + " 🤖 自主模式，目标=" + this.target);
          this.actionTransport.setTarget(this.target);
          this.result = this.actionTransport.calc().getAction();
        }
        break;
        
      default:
        System.err.println("[救护车] ID:" + agentID + " ❓ 未知命令类型: " + this.commandType);
        break;
    }
    
    if (this.result == null) {
      System.err.println("[救护车] ID:" + agentID + " ⚠️ 没有生成有效动作，可能等待或无可执行任务");
    }
    
    return this;
  }

  private Action handleRest() {
    EntityID position = this.agentInfo.getPosition();
    EntityID agentID = this.agentInfo.getID();
    
    if ( this.target == null ) {
      Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType( REFUGE );
      if ( refuges.contains( position ) ) {
        System.err.println("[救护车] ID:" + agentID + " 🏥 已在避难所，休息");
        return new ActionRest();
      } else {
        System.err.println("[救护车] ID:" + agentID + " 📍 寻找避难所");
        this.pathPlanning.setFrom( position );
        this.pathPlanning.setDestination( refuges );
        List<EntityID> path = this.pathPlanning.calc().getResult();
        if ( path != null && path.size() > 0 ) {
          System.err.println("[救护车] ID:" + agentID + " 📍 前往避难所: " + path.get(path.size() - 1));
          return new ActionMove( path );
        } else {
          System.err.println("[救护车] ID:" + agentID + " ⚠️ 无法找到避难所路径");
          return new ActionRest();
        }
      }
    }
    if ( position.getValue() != this.target.getValue() ) {
      System.err.println("[救护车] ID:" + agentID + " 📍 移动前往: " + this.target);
      List<EntityID> path = this.pathPlanning.getResult( position, this.target );
      if ( path != null && path.size() > 0 ) {
        return new ActionMove( path );
      }
    }
    return new ActionRest();
  }

  private boolean isCommandCompleted() {
    Human agent = (Human) this.agentInfo.me();
    EntityID agentID = this.agentInfo.getID();
    
    switch ( this.commandType ) {
      case ACTION_REST:
        boolean restComplete = this.target == null || (this.agentInfo.getPosition().equals(this.target) && agent.getDamage() == 0);
        if (restComplete) {
          System.err.println("[救护车] ID:" + agentID + " ✅ 休息完成");
        }
        return restComplete;
        
      case ACTION_MOVE:
        boolean moveComplete = this.target == null || this.agentInfo.getPosition().equals(this.target);
        if (moveComplete && this.target != null) {
          System.err.println("[救护车] ID:" + agentID + " ✅ 到达目标: " + this.target);
        }
        return moveComplete;
        
      case ACTION_RESCUE:
        if ( this.target == null ) return true;
        Human human = (Human) this.worldInfo.getEntity( this.target );
        if ( human == null ) return true;
        boolean rescueComplete = (human.isBuriednessDefined() && human.getBuriedness() == 0) ||
                                  (human.isHPDefined() && human.getHP() == 0);
        if (rescueComplete) {
          System.err.println("[救护车] ID:" + agentID + " ✅ 救援完成: " + this.target);
        }
        return rescueComplete;
        
      case ACTION_LOAD:
        if ( this.target == null ) return true;
        Human human1 = (Human) this.worldInfo.getEntity( this.target );
        if ( human1 == null ) return true;
        boolean loadComplete = this.agentInfo.someoneOnBoard() != null;
        if (loadComplete) {
          System.err.println("[救护车] ID:" + agentID + " ✅ 装载完成: " + this.target + 
                             " 车上伤员=" + this.agentInfo.someoneOnBoard().getID());
        } else {
          // 检查平民状态
          if (human1.isBuriednessDefined() && human1.getBuriedness() > 0) {
            System.err.println("[救护车] ID:" + agentID + " ⏳ 平民 " + this.target + 
                               " 仍被掩埋，等待消防员 (埋压度=" + human1.getBuriedness() + ")");
          } else if (human1.isDamageDefined() && human1.getDamage() == 0) {
            System.err.println("[救护车] ID:" + agentID + " ⚠️ 平民 " + this.target + " 未受伤，无需装载");
          }
        }
        return loadComplete;
        
      case ACTION_UNLOAD:
        boolean unloadComplete = this.agentInfo.someoneOnBoard() == null;
        if (unloadComplete) {
          System.err.println("[救护车] ID:" + agentID + " ✅ 卸载完成");
        }
        return unloadComplete;
        
      case ACTION_AUTONOMY:
        return true;
        
      default:
        return true;
    }
  }

  public List<EntityID> getResult() {
    return null;
  }
}