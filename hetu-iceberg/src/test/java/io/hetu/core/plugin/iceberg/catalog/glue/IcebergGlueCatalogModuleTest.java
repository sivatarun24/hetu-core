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
package io.hetu.core.plugin.iceberg.catalog.glue;

import com.google.inject.Binder;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class IcebergGlueCatalogModuleTest
{
    private IcebergGlueCatalogModule icebergGlueCatalogModuleUnderTest;

    @BeforeMethod
    public void setUp()
    {
        icebergGlueCatalogModuleUnderTest = new IcebergGlueCatalogModule();
    }

    @Test
    public void testSetup()
    {
        // Setup
        final Binder binder = null;

        // Run the test
        icebergGlueCatalogModuleUnderTest.setup(binder);

        // Verify the results
    }
}