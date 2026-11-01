package khalid.oopgame.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

final class ClientHandler extends SimpleChannelInboundHandler<String> {
    private final ObjectMapper mapper;
    private final RoomManager rooms;

    ClientHandler(ObjectMapper mapper, RoomManager rooms) {
        this.mapper = mapper;
        this.rooms = rooms;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, String input) {
        try {
            JsonNode request = mapper.readTree(input);
            String type = requiredText(request, "type");
            switch (type) {
                case "create_room":
                    createRoom(context, request);
                    break;
                case "join_room":
                    joinRoom(context, request);
                    break;
                case "reconnect":
                    reconnect(context, request);
                    break;
                case "command":
                    command(context, request);
                    break;
                case "get_snapshot":
                    sendSnapshot(context);
                    break;
                case "leave_room":
                    leave(context);
                    break;
                case "ping":
                    send(context, success("pong"));
                    break;
                default:
                    throw new IllegalArgumentException("Tipo de mensaje desconocido.");
            }
        } catch (IllegalArgumentException exception) {
            send(context, error(exception.getMessage()));
        } catch (Exception exception) {
            send(context, error("La solicitud no tiene un formato válido."));
        }
    }

    private void createRoom(ChannelHandlerContext context, JsonNode request) {
        RoomEngine.JoinResult joined = rooms.create(requiredText(request, "name"), context.channel());
        sendJoined(context, joined);
    }

    private void joinRoom(ChannelHandlerContext context, JsonNode request) {
        RoomEngine.JoinResult joined = rooms.join(
            requiredText(request, "roomCode"),
            requiredText(request, "name"),
            context.channel()
        );
        sendJoined(context, joined);
    }

    private void reconnect(ChannelHandlerContext context, JsonNode request) {
        RoomEngine.JoinResult joined = rooms.reconnect(
            requiredText(request, "roomCode"),
            requiredText(request, "playerId"),
            requiredText(request, "reconnectToken"),
            context.channel()
        );
        sendJoined(context, joined);
    }

    private void sendJoined(ChannelHandlerContext context, RoomEngine.JoinResult joined) {
        RoomState.PlayerState player = joined.player;
        context.channel().attr(ServerMain.PLAYER_ID).set(player.id);
        context.channel().attr(ServerMain.ROOM_CODE).set(rooms.roomCodeFor(player.id));
        ObjectNode response = success("joined");
        response.put("playerId", player.id);
        response.put("playerName", player.name);
        response.put("roomCode", rooms.roomCodeFor(player.id));
        response.put("reconnectToken", joined.reconnectToken);
        try {
            response.set("snapshot", mapper.readTree(rooms.snapshot(player.id)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode room snapshot.", exception);
        }
        send(context, response);
    }

    private void command(ChannelHandlerContext context, JsonNode request) {
        String playerId = context.channel().attr(ServerMain.PLAYER_ID).get();
        if (playerId == null) {
            throw new IllegalArgumentException("Primero debes unirte a una sala.");
        }
        JsonNode payload = request.path("payload");
        rooms.command(
            playerId,
            requiredText(payload, "action"),
            optionalText(payload, "fleetId"),
            optionalText(payload, "targetId"),
            optionalInteger(payload, "mapX"),
            optionalInteger(payload, "mapY"),
            optionalFloat(payload, "x"),
            optionalFloat(payload, "y"),
            optionalInteger(payload, "index"),
            optionalText(payload, "itemType")
        );
    }

    private void sendSnapshot(ChannelHandlerContext context) {
        String playerId = context.channel().attr(ServerMain.PLAYER_ID).get();
        if (playerId == null) {
            throw new IllegalArgumentException("Primero debes unirte a una sala.");
        }
        try {
            ObjectNode response = success("snapshot");
            response.set("snapshot", mapper.readTree(rooms.snapshot(playerId)));
            send(context, response);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode room snapshot.", exception);
        }
    }

    private void leave(ChannelHandlerContext context) {
        String playerId = context.channel().attr(ServerMain.PLAYER_ID).getAndSet(null);
        context.channel().attr(ServerMain.ROOM_CODE).set(null);
        if (playerId != null) {
            rooms.disconnect(playerId, context.channel());
        }
        send(context, success("left"));
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        String playerId = context.channel().attr(ServerMain.PLAYER_ID).get();
        if (playerId != null) {
            rooms.disconnect(playerId, context.channel());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        context.close();
    }

    private ObjectNode success(String type) {
        ObjectNode response = mapper.createObjectNode();
        response.put("type", type);
        return response;
    }

    private ObjectNode error(String message) {
        ObjectNode response = success("error");
        response.put("message", message == null ? "Solicitud rechazada." : message);
        return response;
    }

    private void send(ChannelHandlerContext context, ObjectNode message) {
        context.writeAndFlush(message.toString() + "\n");
    }

    private static String requiredText(JsonNode node, String key) {
        String value = optionalText(node, key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Falta el campo requerido: " + key);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer optionalInteger(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException("Campo entero no válido: " + key);
        }
        return value.intValue();
    }

    private static Float optionalFloat(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber() || !Float.isFinite(value.floatValue())) {
            throw new IllegalArgumentException("Campo numérico no válido: " + key);
        }
        return value.floatValue();
    }
}
