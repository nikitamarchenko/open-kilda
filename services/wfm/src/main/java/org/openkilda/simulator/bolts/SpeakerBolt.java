package org.openkilda.simulator.bolts;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.openkilda.messaging.Utils;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.discovery.DiscoverIslCommandData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.event.*;
import org.openkilda.messaging.info.event.SwitchState;
import org.openkilda.simulator.SimulatorTopology;
import org.openkilda.simulator.classes.*;
import org.openkilda.simulator.interfaces.ISwitch;
import org.openkilda.simulator.messages.LinkMessage;
import org.openkilda.simulator.messages.SwitchMessage;
import org.openkilda.simulator.messages.simulator.SimulatorMessage;
import org.openkilda.simulator.messages.simulator.command.AddLinkCommandMessage;
import org.openkilda.simulator.messages.simulator.command.AddSwitchCommand;
import org.openkilda.simulator.messages.simulator.command.PortModMessage;
import org.openkilda.simulator.messages.simulator.command.SwitchModMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

public class SpeakerBolt extends BaseRichBolt {
    private static final Logger logger = LoggerFactory.getLogger(SpeakerBolt.class);
    private OutputCollector collector;
    protected Map<String, ISwitchImpl> switches;
    public enum TupleFields {
        COMMAND,
        DATA;
    }

    protected String makeSwitchMessage(ISwitchImpl sw, SwitchState state) throws IOException {
        SwitchInfoData data = new SwitchInfoData(
                sw.getDpid().toString(),
                state,
                "192.168.0.1", // TODO: need to create these on the fly
                "sw" + sw.getDpid().toString(),
                "Simulated Switch",
                "SimulatorTopology"
        );
        InfoMessage message = new InfoMessage(
                data,
                Instant.now().toEpochMilli(),
                UUID.randomUUID().toString(),
                null);
        return Utils.MAPPER.writeValueAsString(message);
    }

    protected String makePortMessage(ISwitchImpl sw, int portNum, PortChangeType type) throws IOException {
        PortInfoData data = new PortInfoData(
                sw.getDpid().toString(),
                portNum,
                type
        );
        InfoMessage message = new InfoMessage(
                data,
                Instant.now().toEpochMilli(),
                UUID.randomUUID().toString(),
                null);
        return Utils.MAPPER.writeValueAsString(message);
    }

    protected List<Values> addSwitch(AddSwitchCommand data) throws Exception {
        List<Values> values = new ArrayList<>();
        String dpid = data.getDpid();
        if (switches.get(dpid) == null) {
            ISwitchImpl sw = new ISwitchImpl(dpid, data.getNumOfPorts(), PortStateType.DOWN);
            switches.put(sw.getDpid().toString(), sw);
            values.add(new Values("INFO", makeSwitchMessage(sw, SwitchState.ADDED)));
            values.add(new Values("INFO", makeSwitchMessage(sw, SwitchState.ACTIVATED)));
        }
        return values;
    }

    protected List<Values> addSwitch(SwitchMessage switchMessage) throws Exception {
        ISwitchImpl sw = switches.get(switchMessage.getDpid());
        List<Values> values = new ArrayList<>();

        if (sw == null) {
            logger.info("switch does not exist, adding it");
            sw = new ISwitchImpl(switchMessage.getDpid(),
                    switchMessage.getNumOfPorts(), PortStateType.DOWN);
            sw.activate();

            List<LinkMessage> links = switchMessage.getLinks();
            for (LinkMessage l : links) {
                IPortImpl localPort = sw.getPort(l.getLocalPort());
                localPort.setLatency(l.getLatency());
                localPort.setPeerPortNum(l.getPeerPort());
                localPort.setPeerSwitch(l.getPeerSwitch());
                localPort.enable();
            }

            switches.put(sw.getDpid().toString(), sw);

            values.add(new Values("INFO", makeSwitchMessage(sw, SwitchState.ADDED)));
            values.add(new Values("INFO", makeSwitchMessage(sw, SwitchState.ACTIVATED)));

            for (IPortImpl p : sw.getPorts()) {
                PortChangeType changeType = p.isActive() ? PortChangeType.UP : PortChangeType.DOWN; //TODO: see if OF sends DOWN
                if (changeType == PortChangeType.UP) {
                    values.add(new Values("INFO", makePortMessage(sw, p.getNumber(), changeType)));
                }
            }
        }
        return values;
    }

    protected void discoverIsl(Tuple tuple, DiscoverIslCommandData data) throws Exception {
        /*
         * This process is a bit screwy and does put a loop in the topology:
         *
         * 1.  Determine if the source switch is up and the source port is an Active ISL port
         * 2.  Create the IslInfoData package as if it is a working ISL (both ports are active)
         * 3.  Emit tha IslInfoData back to SpeakerBolt with fields grouping but keyed on the second switch to
         *     ensure that the tuple goes to the instance which has that switch in it's switches Map and set command
         *     to DiscoverIslP2
         */

        ISwitchImpl sw = getSwitch(data.getSwitchId());
        if (!sw.isActive()) {
            return;
        }
        IPortImpl localPort = sw.getPort(data.getPortNo());

        if (localPort.isActiveIsl()) {
            List<PathNode> path = new ArrayList<>();
            PathNode path1 = new PathNode(sw.getDpid().toString(), localPort.getNumber(), 0);
            path1.setSegLatency(localPort.getLatency());
            PathNode path2 = new PathNode(localPort.getPeerSwitch(), localPort.getPeerPortNum(), 1);
            path.add(path1);
            path.add(path2);
            IslInfoData islInfoData = new IslInfoData(
                    localPort.getLatency(),
                    path,
                    100000,
                    IslChangeType.DISCOVERED,
                    100000);
            collector.emit(SimulatorTopology.SWITCH_BOLT_STREAM, tuple,
                    new Values(localPort.getPeerSwitch().toLowerCase(), Commands.DO_DISCOVER_ISL_P2_COMMAND.name(), islInfoData));
        }
    }

    protected void discoverIslPartTwo(Tuple tuple, IslInfoData data) throws Exception {
        /*
         * Second part of the discover process.
         *
         * 1.  Grabs a message that has been sent from the first part and thus we know that the source port is
         *     and active ISL.
         * 2.  Check the status of the destination port, in Path[1], and if activeISL then emit to Kafka
         */
        ISwitchImpl sw = getSwitch(data.getPath().get(1).getSwitchId());
        if (!sw.isActive()) {
            return;
        }
        IPortImpl port = sw.getPort(data.getPath().get(1).getPortNo());

        if (port.isActiveIsl()) {
            long now = Instant.now().toEpochMilli();
            InfoMessage infoMessage = new InfoMessage(data, now, "system", null);
            logger.debug("checking isl on: {}", data.toString());
            collector.emit(SimulatorTopology.KAFKA_BOLT_STREAM, tuple,
                    new Values("INFO", Utils.MAPPER.writeValueAsString(infoMessage)));
        }
    }

    protected List<Values> addLink(AddLinkCommandMessage message) throws Exception {
        List<Values> values = new ArrayList<>();
        ISwitchImpl sw;
        IPortImpl port;

        sw = getSwitch("00:00:" + message.getDpid());
        port = sw.getPort(message.getLink().getLocalPort());
        port.setLatency(message.getLink().getLatency());
        port.setPeerSwitch(message.getLink().getPeerSwitch());
        port.setPeerPortNum(message.getLink().getPeerPort());
        port.enable();
        values.add(new Values("INFO", Utils.MAPPER.writeValueAsString(port.makePorChangetMessage())));

        return values;
    }

    protected List<Values> modPort(PortModMessage message) throws Exception {
        List<Values> values = new ArrayList<>();
        ISwitchImpl sw = getSwitch("00:00:" + message.getDpid());
        IPortImpl port = sw.getPort(message.getPortNum());
        port.modPort(message);
        values.add(new Values("INFO", Utils.MAPPER.writeValueAsString(port.makePorChangetMessage())));
        return values;
    }

    public ISwitchImpl getSwitch(String name) throws Exception {
        ISwitchImpl sw = switches.get(name);
        if (sw == null) {
            throw new SimulatorException(String.format("Switch %s not found", name));
        }
        return sw;
    }

    public void doSimulatorCommand(Tuple tuple) throws Exception {
        List<Values> values = new ArrayList<>();
        if (tuple.getFields().contains("command")) {
            String command = tuple.getStringByField("command");
            switch (command) {
                case SimulatorCommands.DO_ADD_SWITCH:
                    //TODO: this is an ugly hack...
                    if (tuple.getValueByField("data") instanceof AddSwitchCommand) {
                        values = addSwitch((AddSwitchCommand) tuple.getValueByField("data"));
                    } else {
                        values = addSwitch((SwitchMessage) tuple.getValueByField("data"));
                    }
                    break;
                case SimulatorCommands.DO_ADD_LINK:
                    values = addLink((AddLinkCommandMessage) tuple.getValueByField("data"));
                    break;
                case SimulatorCommands.DO_PORT_MOD:
                    values = modPort((PortModMessage) tuple.getValueByField("data"));
                    break;
                default:
                    logger.error(String.format("Uknown SimulatorCommand %s", command));
            }
        } else {
            SimulatorMessage message = (SimulatorMessage) tuple.getValueByField("data");
            if (message instanceof SwitchModMessage) {
                SwitchModMessage switchModMessage = (SwitchModMessage) message;
                ISwitchImpl sw = getSwitch(switchModMessage.getDpid());
                //            sw.mod(switchModMessage.getState());
            } else {
                logger.error("Unkown SimulatorMessage {}", message.getClass().getSimpleName());
            }
        }

        if (values.size() > 0) {
            for (Values value : values) {
                logger.debug("emitting: {}", value);
                collector.emit(SimulatorTopology.KAFKA_BOLT_STREAM, tuple, value);
            }
        }
    }

    public void doCommand(Tuple tuple) throws Exception {
        String command = tuple.getStringByField(TupleFields.COMMAND.name());
        List<Values> values = new ArrayList<>();

        if (command.equals(SimulatorCommands.DO_ADD_SWITCH)) {
            values = addSwitch((SwitchMessage) tuple.getValueByField(TupleFields.DATA.name()));
            if (values.size() > 0) {
                for (Values value : values) {
                    logger.debug("emitting: {}", value);
                    collector.emit(SimulatorTopology.KAFKA_BOLT_STREAM, tuple, value);
                }
            }
            return;
        } else if (command.equals(Commands.DO_DISCOVER_ISL_P2_COMMAND.name())) {
            discoverIslPartTwo(tuple, (IslInfoData) tuple.getValueByField(TupleFields.DATA.name()));
            return;
        }

        CommandData data = (CommandData) tuple.getValueByField(TupleFields.DATA.name());
        if (command.equals(Commands.DO_DELETE_FLOW.name())) {

        } else if (command.equals(Commands.DO_DISCOVER_ISL_COMMAND.name())) {
            discoverIsl(tuple, (DiscoverIslCommandData) data);
        } else if (command.equals(Commands.DO_DISCOVER_PATH_COMMAND.name())) {

        } else if (command.equals(Commands.DO_INSTALL_EGRESS_FLOW.name())) {

        } else if (command.equals(Commands.DO_INSTALL_INGRESS_FLOW.name())) {

        } else if (command.equals(Commands.DO_INSTALL_ONESWITCH_FLOW.name())) {

        } else if (command.equals(Commands.DO_INSTALL_TRANSIT_FLOW.name())) {

        } else {
            logger.error("Unknown switch command: {}".format(command));
            return;
        }

        if (values.size() > 0) {
            for (Values value : values) {
                logger.debug("emitting: {}", value);
                collector.emit(SimulatorTopology.KAFKA_BOLT_STREAM, tuple, value);
            }
        }
    }

    @Override
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        this.collector = outputCollector;

        switches = new HashMap<>();
    }

    @Override
    public void execute(Tuple tuple) {
        logger.debug("got tuple: {}", tuple.toString());
        try {
            String tupleSource = tuple.getSourceComponent();

            switch (tupleSource) {
                case SimulatorTopology.COMMAND_BOLT:
                case SimulatorTopology.SWITCH_BOLT:
                    doCommand(tuple);
                    break;
                case SimulatorTopology.SIMULATOR_COMMAND_BOLT:
                    doSimulatorCommand(tuple);
                    break;
                default:
                    logger.error("tuple from unknown source: {}", tupleSource);
            }
        } catch (Exception e) {
            logger.error(e.toString());
            e.printStackTrace();
        } finally {
            collector.ack(tuple);
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        outputFieldsDeclarer.declareStream(SimulatorTopology.KAFKA_BOLT_STREAM, new Fields("key", "message"));
        outputFieldsDeclarer.declareStream(SimulatorTopology.SWITCH_BOLT_STREAM,
                new Fields("dpid", TupleFields.COMMAND.name(), TupleFields.DATA.name()));
    }
}
