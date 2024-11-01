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
package io.prestosql.sql.planner.optimizations;

import io.prestosql.Session;
import io.prestosql.cache.CachedDataStorageProvider;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.spi.plan.PlanNode;
import io.prestosql.spi.plan.PlanNodeIdAllocator;
import io.prestosql.sql.planner.PlanSymbolAllocator;
import io.prestosql.sql.planner.TypeProvider;

public interface PlanOptimizer
{
    PlanNode optimize(PlanNode plan,
            Session session,
            TypeProvider types,
            PlanSymbolAllocator planSymbolAllocator,
            PlanNodeIdAllocator idAllocator,
            WarningCollector warningCollector);

    default PlanNode optimize(PlanNode plan,
                              Session session,
                              TypeProvider types,
                              PlanSymbolAllocator planSymbolAllocator,
                              PlanNodeIdAllocator idAllocator,
                              WarningCollector warningCollector,
                              CachedDataStorageProvider cachedDataStorageProvider)
    {
        return optimize(plan, session, types, planSymbolAllocator, idAllocator, warningCollector);
    }
}
