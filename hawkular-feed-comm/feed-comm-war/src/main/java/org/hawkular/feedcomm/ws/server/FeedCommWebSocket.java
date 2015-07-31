/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.hawkular.feedcomm.ws.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.hawkular.bus.common.BasicMessage;
import org.hawkular.feedcomm.api.ApiDeserializer;
import org.hawkular.feedcomm.api.GenericErrorResponseBuilder;
import org.hawkular.feedcomm.ws.Constants;
import org.hawkular.feedcomm.ws.MsgLogger;
import org.hawkular.feedcomm.ws.command.BinaryData;
import org.hawkular.feedcomm.ws.command.Command;
import org.hawkular.feedcomm.ws.command.CommandContext;

@ServerEndpoint("/feed/{feedId}")
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class FeedCommWebSocket {

    @Inject
    private ConnectedFeeds connectedFeeds;

    @Inject
    private ConnectedUIClients connectedUIClients;

    @Inject
    private FeedListenerGenerator feedListenerGenerator;

    @OnOpen
    public void feedSessionOpen(Session session, @PathParam("feedId") String feedId) {
        MsgLogger.LOG.infoFeedSessionOpened(feedId);
        boolean successfullyAddedSession = connectedFeeds.addSession(feedId, session);
        if (successfullyAddedSession) {
            try {
                feedListenerGenerator.addListeners(feedId);
            } catch (Exception e) {
                MsgLogger.LOG.errorFailedToAddMessageListenersForFeed(feedId, session.getId(), e);
                try {
                    session.close(new CloseReason(CloseCodes.UNEXPECTED_CONDITION, "Internal server error"));
                } catch (IOException ioe) {
                    MsgLogger.LOG.errorf(ioe,
                            "Failed to close feed [%s] session [%s] after internal server error",
                            feedId, session.getId());
                }
            }
        }
    }

    /**
     * When a message is received from a feed, this method will execute the command the feed is asking for.
     *
     * @param nameAndJsonStr the name of the API request followed by "=" followed then by the request's JSON data
     * @param session the client session making the request
     * @param feedId identifies the feed that has connected
     * @return the results of the command invocation; this is sent back to the feed
     */
    @OnMessage
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public String feedMessage(String nameAndJsonStr, Session session, @PathParam("feedId") String feedId) {
        MsgLogger.LOG.infoReceivedMessageFromFeed(feedId);

        String requestClassName = "?";
        BasicMessage response;

        try {
            BasicMessage request = new ApiDeserializer().deserialize(nameAndJsonStr);
            requestClassName = request.getClass().getName();

            Class<? extends Command<?, ?>> commandClass = Constants.VALID_COMMANDS_FROM_FEED.get(requestClassName);
            if (commandClass == null) {
                MsgLogger.LOG.errorInvalidCommandRequestFeed(feedId, requestClassName);
                String errorMessage = "Invalid command request: " + requestClassName;
                response = new GenericErrorResponseBuilder().setErrorMessage(errorMessage).build();
            } else {
                CommandContext context = new CommandContext(connectedFeeds, connectedUIClients,
                        feedListenerGenerator.getConnectionFactory());
                Command command = commandClass.newInstance();
                response = command.execute(request, null, context);
            }
        } catch (Throwable t) {
            MsgLogger.LOG.errorCommandExecutionFailureFeed(requestClassName, feedId, t);
            String errorMessage = "Command failed [" + requestClassName + "]";
            response = new GenericErrorResponseBuilder()
                    .setThrowable(t)
                    .setErrorMessage(errorMessage)
                    .build();

        }

        String responseText = (response == null) ? null : ApiDeserializer.toHawkularFormat(response);
        return responseText;
    }

    /**
     * When a binary message is received from a feed, this method will execute the command the client
     * is asking for.
     *
     * @param binaryDataStream contains the JSON request and additional binary data
     * @param session the client session making the request
     * @param feedId identifies the feed that has connected
     * @return the results of the command invocation; this is sent back to the feed
     */
    @OnMessage
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public String feedBinaryData(InputStream binaryDataStream, Session session, @PathParam("feedId") String feedId) {
        MsgLogger.LOG.infoReceivedBinaryDataFromFeed(feedId);

        String requestClassName = "?";
        BasicMessage response;

        try {
            Map<BasicMessage, byte[]> requestMap = new ApiDeserializer().deserialize(binaryDataStream);
            BasicMessage request = requestMap.keySet().iterator().next();
            byte[] inMemoryData = requestMap.values().iterator().next();
            BinaryData binaryData = new BinaryData(inMemoryData, binaryDataStream);
            requestClassName = request.getClass().getName();

            Class<? extends Command<?, ?>> commandClass = Constants.VALID_COMMANDS_FROM_FEED.get(requestClassName);
            if (commandClass == null) {
                MsgLogger.LOG.errorInvalidCommandRequestFeed(feedId, requestClassName);
                String errorMessage = "Invalid command request: " + requestClassName;
                response = new GenericErrorResponseBuilder().setErrorMessage(errorMessage).build();
            } else {
                CommandContext context = new CommandContext(connectedFeeds, connectedUIClients,
                        feedListenerGenerator.getConnectionFactory());
                Command command = commandClass.newInstance();
                response = command.execute(request, binaryData, context);
            }
        } catch (Throwable t) {
            MsgLogger.LOG.errorCommandExecutionFailureFeed(requestClassName, feedId, t);
            String errorMessage = "Command failed [" + requestClassName + "]";
            response = new GenericErrorResponseBuilder()
                    .setThrowable(t)
                    .setErrorMessage(errorMessage)
                    .build();

        }

        String responseText = (response == null) ? null : ApiDeserializer.toHawkularFormat(response);
        return responseText;
    }

    @OnClose
    public void feedSessionClose(Session session, CloseReason reason, @PathParam("feedId") String feedId) {
        MsgLogger.LOG.infoFeedSessionClosed(feedId, reason);
        boolean removed = connectedFeeds.removeSession(feedId, session) != null;
        if (removed) {
            feedListenerGenerator.removeListeners(feedId);
        }
    }
}
