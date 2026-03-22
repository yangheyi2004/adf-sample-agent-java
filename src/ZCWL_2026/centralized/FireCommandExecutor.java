package ZCWL_2026.centralized;

import adf.core.agent.communication.standard.bundle.centralized.CommandFire;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.centralized.CommandExecutor;
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

import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

public class FireCommandExecutor extends adf.core.component.centralized.CommandExecutor {

  private static final int ACTION_UNKNOWN  = -1;
  private static final int ACTION_REST     = CommandAmbulance.ACTION_REST;
  private static final int ACTION_MOVE     = CommandAmbulance.ACTION_MOVE;
  private static final int ACTION_RESCUE   = CommandAmbulance.ACTION_RESCUE;
  private static final int ACTION_AUTONOMY = CommandAmbulance.ACTION_AUTONOMY;

  private PathPlanning     pathPlanning;

  private ExtAction        actionFireRescue;
  private ExtAction        actionFireFighting;
  private ExtAction        actionExtMove;

  private int              commandType;
  private EntityID         target;
  private EntityID         commanderID;


  public FireCommandExecutor( AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData ) {
    super( ai, wi, si, moduleManager, developData );
    this.commandType = ACTION_UNKNOWN;
    switch ( scenarioInfo.getMode() ) {
      case PRECOMPUTATION_PHASE:
        this.pathPlanning = moduleManager.getModule(
            "FireCommandExecutor.PathPlanning",
            "ZCWL_2026.module.algorithm.PathPlanning" );
        this.actionFireRescue = moduleManager.getExtAction(
            "FireCommandExecutor.ActionFireRescue",
            "ZCWL_2026.extraction.ActionFireRescue" );
        this.actionFireFighting = moduleManager.getExtAction(
            "FireCommandExecutor.ActionFireFighting",
            "ZCWL_2026.extraction.ActionFireFighting" );
        this.actionExtMove = moduleManager.getExtAction(
            "FireCommandExecutor.ActionExtMove",
            "ZCWL_2026.extraction.ActionExtMove" );
        break;
      case PRECOMPUTED:
        this.pathPlanning = moduleManager.getModule(
            "FireCommandExecutor.PathPlanning",
            "ZCWL_2026.module.algorithm.PathPlanning" );
        this.actionFireRescue = moduleManager.getExtAction(
            "FireCommandExecutor.ActionFireRescue",
            "ZCWL_2026.extraction.ActionFireRescue" );
        this.actionFireFighting = moduleManager.getExtAction(
            "FireCommandExecutor.ActionFireFighting",
            "ZCWL_2026.extraction.ActionFireFighting" );
        this.actionExtMove = moduleManager.getExtAction(
            "FireCommandExecutor.ActionExtMove",
            "ZCWL_2026.extraction.ActionExtMove" );
        break;
      case NON_PRECOMPUTE:
        this.pathPlanning = moduleManager.getModule(
            "FireCommandExecutor.PathPlanning",
            "ZCWL_2026.module.algorithm.PathPlanning" );
        this.actionFireRescue = moduleManager.getExtAction(
            "FireCommandExecutor.ActionFireRescue",
            "ZCWL_2026.extraction.ActionFireRescue" );
        this.actionFireFighting = moduleManager.getExtAction(
            "FireCommandExecutor.ActionFireFighting",
            "ZCWL_2026.extraction.ActionFireFighting" );
        this.actionExtMove = moduleManager.getExtAction(
            "FireCommandExecutor.ActionExtMove",
            "ZCWL_2026.extraction.ActionExtMove" );
        break;
    }
  }


@Override
public CommandExecutor setCommand(CommunicationMessage command) {
    if (command instanceof CommandFire) {
        CommandFire cmd = (CommandFire) command;
        EntityID agentID = this.agentInfo.getID();
        if (cmd.isToIDDefined() && Objects.requireNonNull(cmd.getToID())
                .getValue() == agentID.getValue()) {
            this.commandType = cmd.getAction();
            this.target = cmd.getTargetID();
            this.commanderID = cmd.getSenderID();
        }
    }
    return this;
}


  @Override
  public CommandExecutor updateInfo( MessageManager messageManager ) {
    super.updateInfo( messageManager );
    if ( this.getCountUpdateInfo() >= 2 ) {
      return this;
    }
    this.pathPlanning.updateInfo( messageManager );
    this.actionFireRescue.updateInfo( messageManager );
    this.actionFireFighting.updateInfo( messageManager );
    this.actionExtMove.updateInfo( messageManager );

    if ( this.isCommandCompleted() ) {
      if ( this.commandType != ACTION_UNKNOWN ) {
        messageManager.addMessage(
            new MessageReport( true, true, false, this.commanderID ) );

        this.commandType = ACTION_UNKNOWN;
        this.target = null;
        this.commanderID = null;
      }
    }
    return this;
  }


  @Override
  public CommandExecutor precompute( PrecomputeData precomputeData ) {
    super.precompute( precomputeData );
    if ( this.getCountPrecompute() >= 2 ) {
      return this;
    }
    this.pathPlanning.precompute( precomputeData );
    this.actionFireRescue.precompute( precomputeData );
    this.actionFireFighting.precompute( precomputeData );
    this.actionExtMove.precompute( precomputeData );
    return this;
  }


  @Override
  public CommandExecutor resume( PrecomputeData precomputeData ) {
    super.resume( precomputeData );
    if ( this.getCountResume() >= 2 ) {
      return this;
    }
    this.pathPlanning.resume( precomputeData );
    this.actionFireRescue.resume( precomputeData );
    this.actionFireFighting.resume( precomputeData );
    this.actionExtMove.resume( precomputeData );
    return this;
  }


  @Override
  public CommandExecutor preparate() {
    super.preparate();
    if ( this.getCountPreparate() >= 2 ) {
      return this;
    }
    this.pathPlanning.preparate();
    this.actionFireRescue.preparate();
    this.actionFireFighting.preparate();
    this.actionExtMove.preparate();
    return this;
  }


  @Override
  public CommandExecutor calc() {
    this.result = null;
    switch ( this.commandType ) {
      case ACTION_REST:
        EntityID position = this.agentInfo.getPosition();
        if ( this.target == null ) {
          Collection<EntityID> refuges = this.worldInfo
              .getEntityIDsOfType( REFUGE );
          if ( refuges.contains( position ) ) {
            this.result = new ActionRest();
          } else {
            this.pathPlanning.setFrom( position );
            this.pathPlanning.setDestination( refuges );
            List<EntityID> path = this.pathPlanning.calc().getResult();
            if ( path != null && path.size() > 0 ) {
              this.result = new ActionMove( path );
            } else {
              this.result = new ActionRest();
            }
          }
          return this;
        }
        if ( position.getValue() != this.target.getValue() ) {
          List<EntityID> path = this.pathPlanning.getResult( position,
              this.target );
          if ( path != null && path.size() > 0 ) {
            this.result = new ActionMove( path );
            return this;
          }
        }
        this.result = new ActionRest();
        return this;
      case ACTION_MOVE:
        if ( this.target != null ) {
          this.result = this.actionExtMove.setTarget( this.target ).calc()
              .getAction();
        }
        return this;
      case ACTION_RESCUE:
        if ( this.target != null ) {
          this.result = this.actionFireRescue.setTarget( this.target ).calc()
              .getAction();
        }
        return this;
      case ACTION_AUTONOMY:
        if ( this.target == null ) {
          return this;
        }
        StandardEntity targetEntity = this.worldInfo.getEntity( this.target );
        if ( targetEntity instanceof Area ) {
          this.result = this.actionExtMove.setTarget( this.target ).calc()
              .getAction();
        } else if ( targetEntity instanceof Human ) {
          this.result = this.actionFireRescue.setTarget( this.target ).calc()
              .getAction();
        }
    }
    return this;
  }


  private boolean isCommandCompleted() {
    Human agent = (Human) this.agentInfo.me();
    switch ( this.commandType ) {
      case ACTION_REST:
        if ( this.target == null ) {
          return ( agent.getDamage() == 0 );
        }
        if ( Objects.requireNonNull( this.worldInfo.getEntity( this.target ) )
            .getStandardURN() == REFUGE ) {
          if ( agent.getPosition().getValue() == this.target.getValue() ) {
            return ( agent.getDamage() == 0 );
          }
        }
        return false;
      case ACTION_MOVE:
        return this.target == null || this.agentInfo.getPosition()
            .getValue() == this.target.getValue();
      case ACTION_RESCUE:
        if ( this.target == null ) {
          return true;
        }
        Human human = (Human) Objects
            .requireNonNull( this.worldInfo.getEntity( this.target ) );
        return human.isBuriednessDefined() && human.getBuriedness() == 0
            || ( human.isHPDefined() && human.getHP() == 0 );
      case ACTION_AUTONOMY:
        if ( this.target != null ) {
          StandardEntity targetEntity = this.worldInfo.getEntity( this.target );
          if ( targetEntity instanceof Area ) {
            this.commandType = ACTION_MOVE;
            return this.isCommandCompleted();
          } else if ( targetEntity instanceof Human ) {
            Human h = (Human) targetEntity;
            if ( ( h.isHPDefined() && h.getHP() == 0 ) ) {
              return true;
            }
            if ( h.getStandardURN() == CIVILIAN ) {
              this.commandType = ACTION_RESCUE;
            }
            return this.isCommandCompleted();
          }
        }
        return true;
    }
    return true;
  }

  // 保留第一个代码的空getResult方法以保持兼容性
  public List<EntityID> getResult() {
    return null;
  }
}
