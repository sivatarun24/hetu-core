/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.metadata;

import com.google.common.collect.ImmutableList;
import io.prestosql.spi.TrinoException;
import io.prestosql.spi.connector.ConnectorViewDefinition;
import io.prestosql.spi.connector.QualifiedObjectName;
import io.prestosql.spi.security.Identity;
import io.prestosql.spi.type.TypeId;
import io.prestosql.spi.type.TypeSignature;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.spi.StandardErrorCode.INVALID_VIEW;
import static java.util.Objects.requireNonNull;

public class ViewDefinition
{
    private final String originalSql;
    private final Optional<String> catalog;
    private final Optional<String> schema;
    private final List<ViewColumn> columns;
    private final Optional<String> comment;
    private final Optional<Identity> runAsIdentity;

    public ViewDefinition(
            String originalSql,
            Optional<String> catalog,
            Optional<String> schema,
            List<ViewColumn> columns,
            Optional<String> comment,
            Optional<Identity> runAsIdentity)
    {
        this.originalSql = requireNonNull(originalSql, "originalSql is null");
        this.catalog = requireNonNull(catalog, "catalog is null");
        this.schema = requireNonNull(schema, "schema is null");
        this.columns = ImmutableList.copyOf(requireNonNull(columns, "columns is null"));
        this.comment = requireNonNull(comment, "comment is null");
        this.runAsIdentity = requireNonNull(runAsIdentity, "runAsIdentity is null");
        checkArgument(!schema.isPresent() || catalog.isPresent(), "catalog must be present if schema is present");
        checkArgument(!columns.isEmpty(), "columns list is empty");
    }

    public ViewDefinition(QualifiedObjectName viewName, ConnectorViewDefinition view)
    {
        this(viewName, view, view.getOwner().map(Identity::ofUser));
    }

    public ViewDefinition(QualifiedObjectName viewName, ConnectorViewDefinition view, Identity runAsIdentityOverride)
    {
        this(viewName, view, Optional.of(runAsIdentityOverride));
    }

    private ViewDefinition(QualifiedObjectName viewName, ConnectorViewDefinition view, Optional<Identity> runAsIdentity)
    {
        requireNonNull(view, "view is null");
        this.originalSql = view.getOriginalSql();
        this.catalog = view.getCatalog();
        this.schema = view.getSchema();
        this.columns = view.getColumns().stream()
                .map(column -> new ViewColumn(column.getName(), TypeId.of("1")))
                .collect(toImmutableList());
        this.comment = view.getComment();
        this.runAsIdentity = runAsIdentity;
        if (view.isRunAsInvoker() && runAsIdentity.isPresent()) {
            throw new TrinoException(INVALID_VIEW, "Run-as identity cannot be set for a run-as invoker view: " + viewName);
        }
        if (!view.isRunAsInvoker() && !runAsIdentity.isPresent()) {
            throw new TrinoException(INVALID_VIEW, "Run-as identity must be set for a run-as definer view: " + viewName);
        }
    }

    public String getOriginalSql()
    {
        return originalSql;
    }

    public Optional<String> getCatalog()
    {
        return catalog;
    }

    public Optional<String> getSchema()
    {
        return schema;
    }

    public List<ViewColumn> getColumns()
    {
        return columns;
    }

    public Optional<String> getComment()
    {
        return comment;
    }

    public boolean isRunAsInvoker()
    {
        return !runAsIdentity.isPresent();
    }

    public Optional<Identity> getRunAsIdentity()
    {
        return runAsIdentity;
    }

    public ConnectorViewDefinition toConnectorViewDefinition()
    {
        return new ConnectorViewDefinition(
                originalSql,
                catalog,
                schema,
                columns.stream()
                        .map(column -> new ConnectorViewDefinition.ViewColumn(column.getName(), new TypeSignature(column.getType().toString())))
                        .collect(toImmutableList()),
                runAsIdentity.map(Identity::getUser),
                !runAsIdentity.isPresent());
    }

    @Override
    public String toString()
    {
        return toStringHelper(this).omitNullValues()
                .add("originalSql", originalSql)
                .add("catalog", catalog.orElse(null))
                .add("schema", schema.orElse(null))
                .add("columns", columns)
                .add("comment", comment.orElse(null))
                .add("runAsIdentity", runAsIdentity.orElse(null))
                .toString();
    }
}
