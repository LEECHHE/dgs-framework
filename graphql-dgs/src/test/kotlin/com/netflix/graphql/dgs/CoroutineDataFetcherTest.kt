/*
 * Copyright 2021 Netflix, Inc.
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

package com.netflix.graphql.dgs

import com.netflix.graphql.dgs.context.DgsContext
import com.netflix.graphql.dgs.internal.DgsSchemaProvider
import graphql.ExecutionInput
import graphql.GraphQL
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Percentage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.context.ApplicationContext
import java.util.*
import kotlin.system.measureTimeMillis

@ExtendWith(MockKExtension::class)
class CoroutineDataFetcherTest {
    @MockK
    lateinit var applicationContextMock: ApplicationContext

    @Test
    fun `Suspend functions should be supported as datafetchers`() {
        val fetcher = object : Any() {
            @DgsQuery
            suspend fun concurrent(@InputArgument from: Int, to: Int): Int = coroutineScope {
                var sum = 0

                repeat(from.rangeTo(to).count()) {
                    sum++
                    delay(50)
                }

                sum
            }
        }

        every { applicationContextMock.getBeansWithAnnotation(DgsComponent::class.java) } returns mapOf(Pair("concurrentFetcher", fetcher))
        every { applicationContextMock.getBeansWithAnnotation(DgsScalar::class.java) } returns emptyMap()

        val provider = DgsSchemaProvider(applicationContextMock, Optional.empty(), Optional.empty(), Optional.empty())
        val schema = provider.schema(
            """
            type Query {
                concurrent(from: Int, to: Int): Int
            }           
            """.trimIndent()
        )

        val build = GraphQL.newGraphQL(schema).build()

        val context = DgsContext(
            null,
            null,
            CoroutineScope(Dispatchers.Default)
        )

        val concurrentTime = measureTimeMillis {
            val executionResult = build.execute(
                ExecutionInput.newExecutionInput().context(context).query(
                    """
            {
                first: concurrent(from: 1, to: 10)
                second: concurrent(from: 1, to: 10)               
                third: concurrent(from: 1, to: 10)               
                fourth: concurrent(from: 1, to: 10)               
            }
                    """.trimIndent()
                ).build()
            )

            assertThat(executionResult.isDataPresent).isTrue()
        }

        val singleTime = measureTimeMillis {
            val executionResult = build.execute(
                ExecutionInput.newExecutionInput().context(context).query(
                    """
            {
                first: concurrent(from: 1, to: 10)
            }
                    """.trimIndent()
                ).build()
            )

            assertThat(executionResult.isDataPresent).isTrue()
        }

        assertThat(concurrentTime).isCloseTo(singleTime, Percentage.withPercentage(200.0))
    }
}
