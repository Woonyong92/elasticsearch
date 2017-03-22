/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.persistent;

import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.MasterNodeOperationRequestBuilder;
import org.elasticsearch.action.support.master.MasterNodeRequest;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportResponse.Empty;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.Objects;

/**
 * Action that is used by executor node to indicate that the persistent action finished or failed on the node and needs to be
 * removed from the cluster state in case of successful completion or restarted on some other node in case of failure.
 */
public class CompletionPersistentTaskAction extends Action<CompletionPersistentTaskAction.Request,
        CompletionPersistentTaskAction.Response,
        CompletionPersistentTaskAction.RequestBuilder> {

    public static final CompletionPersistentTaskAction INSTANCE = new CompletionPersistentTaskAction();
    public static final String NAME = "cluster:admin/persistent/completion";

    private CompletionPersistentTaskAction() {
        super(NAME);
    }

    @Override
    public RequestBuilder newRequestBuilder(ElasticsearchClient client) {
        return new RequestBuilder(client, this);
    }

    @Override
    public Response newResponse() {
        return new Response();
    }

    public static class Request extends MasterNodeRequest<Request> {

        private long taskId;

        private Exception exception;

        public Request() {

        }

        public Request(long taskId, Exception exception) {
            this.taskId = taskId;
            this.exception = exception;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            taskId = in.readLong();
            exception = in.readException();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeLong(taskId);
            out.writeException(exception);
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Request request = (Request) o;
            return taskId == request.taskId &&
                    Objects.equals(exception, request.exception);
        }

        @Override
        public int hashCode() {
            return Objects.hash(taskId, exception);
        }
    }

    public static class Response extends AcknowledgedResponse {
        public Response() {
            super();
        }

        public Response(boolean acknowledged) {
            super(acknowledged);
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            readAcknowledged(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            writeAcknowledged(out);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AcknowledgedResponse that = (AcknowledgedResponse) o;
            return isAcknowledged() == that.isAcknowledged();
        }

        @Override
        public int hashCode() {
            return Objects.hash(isAcknowledged());
        }

    }

    public static class RequestBuilder extends MasterNodeOperationRequestBuilder<CompletionPersistentTaskAction.Request,
            CompletionPersistentTaskAction.Response, CompletionPersistentTaskAction.RequestBuilder> {

        protected RequestBuilder(ElasticsearchClient client, CompletionPersistentTaskAction action) {
            super(client, action, new Request());
        }
    }

    public static class TransportAction extends TransportMasterNodeAction<Request, Response> {

        private final PersistentTasksClusterService persistentTasksClusterService;

        @Inject
        public TransportAction(Settings settings, TransportService transportService, ClusterService clusterService,
                               ThreadPool threadPool, ActionFilters actionFilters,
                               PersistentTasksClusterService persistentTasksClusterService,
                               IndexNameExpressionResolver indexNameExpressionResolver) {
            super(settings, CompletionPersistentTaskAction.NAME, transportService, clusterService, threadPool, actionFilters,
                    indexNameExpressionResolver, Request::new);
            this.persistentTasksClusterService = persistentTasksClusterService;
        }

        @Override
        protected String executor() {
            return ThreadPool.Names.GENERIC;
        }

        @Override
        protected Response newResponse() {
            return new Response();
        }

        @Override
        protected ClusterBlockException checkBlock(Request request, ClusterState state) {
            // Cluster is not affected but we look up repositories in metadata
            return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
        }

        @Override
        protected final void masterOperation(final Request request, ClusterState state, final ActionListener<Response> listener) {
            persistentTasksClusterService.completeOrRestartPersistentTask(request.taskId, request.exception, new ActionListener<Empty>() {
                @Override
                public void onResponse(Empty empty) {
                    listener.onResponse(newResponse());
                }

                @Override
                public void onFailure(Exception e) {
                    listener.onFailure(e);
                }
            });
        }
    }
}


