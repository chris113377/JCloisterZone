package com.jcloisterzone.game.phase;

import com.jcloisterzone.Player;
import com.jcloisterzone.action.TilePlacementAction;
import com.jcloisterzone.board.*;
import com.jcloisterzone.board.pointer.FeaturePointer;
import com.jcloisterzone.event.PlayEvent.PlayEventMeta;
import com.jcloisterzone.event.TileDiscardedEvent;
import com.jcloisterzone.event.TokenPlacedEvent;
import com.jcloisterzone.game.RandomGenerator;
import com.jcloisterzone.game.capability.*;
import com.jcloisterzone.game.capability.BridgeCapability.BridgeToken;
import com.jcloisterzone.game.state.ActionsState;
import com.jcloisterzone.game.state.Flag;
import com.jcloisterzone.game.state.GameState;
import com.jcloisterzone.reducers.PlaceBridge;
import com.jcloisterzone.reducers.PlaceTile;
import com.jcloisterzone.io.message.PassMessage;
import com.jcloisterzone.io.message.PlaceTileMessage;
import io.vavr.Tuple2;
import io.vavr.collection.Queue;
import io.vavr.collection.Set;
import io.vavr.collection.Vector;


public class TilePhase extends Phase {

    public TilePhase(RandomGenerator random) {
        super(random);
    }

    public GameState drawTile(GameState state) {
        TilePack tps = state.getTilePack();
        Tuple2<Tile, TilePack> t = tps.drawTile(getRandom());
        return state.setTilePack(t._2).setDrawnTile(t._1);
    }

    public GameState drawTile(GameState state, String tileId) {
        TilePack tps = state.getTilePack();
        Tuple2<Tile, TilePack> t = tps.drawTile(tileId);
        return state.setTilePack(t._2).setDrawnTile(t._1);
    }

    @Override
    public StepResult enter(GameState state) {
        for (;;) {
            BazaarCapabilityModel bazaarModel = state.getCapabilityModel(BazaarCapability.class);
            Queue<BazaarItem> bazaarSupply = bazaarModel == null ? null : bazaarModel.getSupply();

            if (bazaarSupply != null && !bazaarSupply.isEmpty()) { // can be empty when reinvoked after discard/pass
                Tuple2<BazaarItem, Queue<BazaarItem>> t = bazaarSupply.dequeue();
                BazaarItem item = t._1;
                if (item.getOwner().equals(state.getTurnPlayer())) {
                    bazaarSupply = t._2;
                    bazaarModel = bazaarModel.setSupply(bazaarSupply);
                    state = state.setCapabilityModel(BazaarCapability.class, bazaarModel);
                    state = state.setDrawnTile(item.getTile());
                } /* else:
                    very rare case when not legal placement has been found for prev bazaar tile
                    (Or player pass tile placement (allowed when only legal placement is with bridge)
                    Draw random tile instead
                */
            }

            if (state.getDrawnTile() == null) {
                // regular flow (not tile from Bazaar supply
                TilePack tilePack = state.getTilePack();
                boolean packIsEmpty = tilePack.isEmpty();

                if (packIsEmpty && bazaarSupply != null) {
                    // Very edge case: no match for bazaar tile + no remaining tile in pack.
                    // Skip player's turns
                    return next(state, CleanUpTurnPhase.class);
                }

                //Abbey special case, every player has opportunity to place own abbey at the end.
                if (packIsEmpty && state.getCapabilities().contains(AbbeyCapability.class)) {
                    Integer endPlayerIdx = state.getCapabilityModel(AbbeyCapability.class);
                    Player turnPlayer = state.getTurnPlayer();
                    if (endPlayerIdx == null) {
                        //tile pack has been depleted jut now
                        endPlayerIdx = turnPlayer.getPrevPlayer(state).getIndex();
                        state = state.setCapabilityModel(AbbeyCapability.class, endPlayerIdx);
                    }
                    if (endPlayerIdx != turnPlayer.getIndex()) {
                        return next(state, CleanUpTurnPartPhase.class);
                    }
                    // otherwise proceed to game over
                }

                // Tile Pack is empty
                if (packIsEmpty) {
                    if (state.hasCapability(CountCapability.class)) {
                        return next(state, CocFinalScoringPhase.class);
                    } else {
                        return next(state, GameOverPhase.class);
                    }
                }

                state = drawTile(state);
            }

            Tile tile = state.getDrawnTile();
            Set<PlacementOption> placements = state.getTilePlacements(tile).toSet();

            if (placements.isEmpty()) {
                state = discardTile(state);
            } else {
                TilePlacementAction action = new TilePlacementAction(tile, placements);

                boolean canPass = placements.find(p -> p.getMandatoryBridge() == null).isEmpty();

                state = state.setPlayerActions(new ActionsState(
                    state.getTurnPlayer(),
                    Vector.of(action),
                    canPass
                ));

                return promote(state);
            }
        }
    }

    @Override
    @PhaseMessageHandler
    public StepResult handlePass(GameState state, PassMessage msg) {
        TilePlacementAction action = (TilePlacementAction) state.getAction();

        if (action.getOptions().find(p -> p.getMandatoryBridge() == null).isDefined()) {
            throw new IllegalStateException("Pass is not allowed");
        }

        state = discardTile(state);
        state = state.setDrawnTile(null);
        return enter(state);
    }

    @PhaseMessageHandler
    public StepResult handlePlaceTile(GameState state, PlaceTileMessage msg) {
        Tile tile = state.getDrawnTile();
        Position pos = msg.getPosition();
        Rotation rot = msg.getRotation();
        Player player = state.getActivePlayer();

        assert tile.getId().equals(msg.getTileId()) : String.format("%s received, but %s is drawn", msg.getTileId(), tile.getId());

        TilePlacementAction action = (TilePlacementAction) state.getPlayerActions().getActions().get();

        PlacementOption placement = action.getOptions()
            .find(tp -> tp.getPosition().equals(pos) && tp.getRotation().equals(rot))
            .getOrElseThrow(() -> new IllegalArgumentException("Invalid placement " + pos + "," + rot));

        FeaturePointer mandatoryBridge = placement.getMandatoryBridge();

        if (mandatoryBridge != null) {
            state = state.mapPlayers(ps ->
                ps.addTokenCount(player.getIndex(), BridgeToken.BRIDGE, -1)
            );
            state = state.mapCapabilityModel(BridgeCapability.class, model -> model.add(mandatoryBridge));

            Position bridgePos = mandatoryBridge.getPosition();
            Location bridgeLoc = mandatoryBridge.getLocation();
            if (bridgePos.equals(pos)) {
                //bridge on just placed tile -> just update tile definition
                tile = tile.addBridge(bridgeLoc.rotateCCW(rot));
                state = state.mapCapabilityModel(BridgeCapability.class, model -> model.add(mandatoryBridge));
            } else {
                state = (new PlaceBridge(mandatoryBridge, true)).apply(state);
            }
        }

        state = (new PlaceTile(tile, msg.getPosition(), msg.getRotation())).apply(state);

        if (mandatoryBridge != null) {
            state = state.appendEvent(
                new TokenPlacedEvent(PlayEventMeta.createWithPlayer(player), BridgeToken.BRIDGE, mandatoryBridge)
            );
        }

        if (tile.hasModifier(HillCapability.HILL)) {
        	TilePack tilePack = state.getTilePack();
        	if (!tilePack.isEmpty()) {
        		state = state.setTilePack(tilePack.increaseHiddenUnderHills());
        	}
        }

        state = clearActions(state);
        state = state.setDrawnTile(null);

        if (tile.hasModifier(BazaarCapability.BAZAAR)) {
            BazaarCapabilityModel model = state.getCapabilityModel(BazaarCapability.class);
            //Do not trigger another auction is current is not resolved
            if (model.getSupply() == null) {
                state = state.addFlag(Flag.BAZAAR_AUCTION);
            }
        }

        return next(state);
    }

    private GameState discardTile(GameState state) {
        Tile tile = state.getDrawnTile();
        return state
            .setDrawnTile(null)
            .setDiscardedTiles(state.getDiscardedTiles().append(tile))
            .appendEvent(new TileDiscardedEvent(tile));
    }
}
