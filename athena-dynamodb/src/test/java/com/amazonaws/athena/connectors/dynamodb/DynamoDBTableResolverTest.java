/*-
 * #%L
 * athena-dynamodb
 * %%
 * Copyright (C) 2019 Amazon Web Services
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.amazonaws.athena.connectors.dynamodb;

import com.amazonaws.athena.connector.lambda.ThrottlingInvoker;
import com.amazonaws.athena.connector.lambda.exceptions.AthenaConnectorException;
import com.amazonaws.athena.connectors.dynamodb.resolver.DynamoDBTableResolver;
import com.amazonaws.athena.connectors.dynamodb.util.DDBTableUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.LimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.glue.model.FederationSourceErrorCode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DynamoDBTableResolverTest
{
    private static final ThrottlingInvoker.ExceptionFilter NO_OP_FILTER = ex -> false;

    @Mock
    private DynamoDbClient ddbClient;

    private DynamoDBTableResolver tableResolver;

    @Before
    public void setUp()
    {
        ThrottlingInvoker invoker = ThrottlingInvoker.newDefaultBuilder(NO_OP_FILTER, Collections.emptyMap()).build();
        tableResolver = new DynamoDBTableResolver(invoker, ddbClient);
    }

    @Test
    public void testGetTableSchema_ExceptionMapping()
    {
        List<Object[]> testCases = Arrays.asList(
                // {exception, expectedErrorCode, description}
                new Object[]{
                        DynamoDbException.builder()
                                .message("User: arn:aws:sts::123456789012:assumed-role/test_role/session is not authorized to perform: dynamodb:Scan on resource: arn:aws:dynamodb:us-east-1:123456789012:table/test_table")
                                .statusCode(400)
                                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDeniedException").errorMessage("Access Denied").build())
                                .build(),
                        FederationSourceErrorCode.ACCESS_DENIED_EXCEPTION,
                        "AccessDenied via message content"
                },
                new Object[]{
                        DynamoDbException.builder()
                                .message("The client did not correctly sign the request")
                                .statusCode(400)
                                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDeniedException").errorMessage("Access Denied").build())
                                .build(),
                        FederationSourceErrorCode.ACCESS_DENIED_EXCEPTION,
                        "AccessDenied without 'not authorized' in message"
                },
                new Object[]{
                        ProvisionedThroughputExceededException.builder().message("The level of configured provisioned throughput for the table was exceeded").statusCode(400).build(),
                        FederationSourceErrorCode.THROTTLING_EXCEPTION,
                        "ProvisionedThroughputExceededException"
                },
                new Object[]{
                        RequestLimitExceededException.builder().message("Throughput exceeds the current throughput limit for your account").statusCode(400).build(),
                        FederationSourceErrorCode.THROTTLING_EXCEPTION,
                        "RequestLimitExceededException"
                },
                new Object[]{
                        LimitExceededException.builder().message("Too many operations for a given subscriber").statusCode(400).build(),
                        FederationSourceErrorCode.THROTTLING_EXCEPTION,
                        "LimitExceededException"
                },
                new Object[]{
                        DynamoDbException.builder()
                                .message("1 validation error detected")
                                .statusCode(400)
                                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ValidationException").errorMessage("1 validation error detected").build())
                                .build(),
                        FederationSourceErrorCode.INVALID_INPUT_EXCEPTION,
                        "ValidationException"
                },
                new Object[]{
                        DynamoDbException.builder()
                                .message("Internal Server Error")
                                .statusCode(500)
                                .awsErrorDetails(AwsErrorDetails.builder().errorCode("InternalServerError").errorMessage("Internal Server Error").build())
                                .build(),
                        FederationSourceErrorCode.INTERNAL_SERVICE_EXCEPTION,
                        "InternalServerError"
                }
        );

        for (Object[] testCase : testCases) {
            reset(ddbClient);
            RuntimeException exception = (RuntimeException) testCase[0];
            FederationSourceErrorCode expectedCode = (FederationSourceErrorCode) testCase[1];
            String description = (String) testCase[2];

            when(ddbClient.scan(any(ScanRequest.class))).thenThrow(exception);

            try {
                tableResolver.getTableSchema("test_table");
                fail("Expected AthenaConnectorException for: " + description);
            }
            catch (Exception e) {
                assertTrue("Expected AthenaConnectorException for: " + description, e instanceof AthenaConnectorException);
                assertEquals("Wrong error code for: " + description,
                        expectedCode.toString(), ((AthenaConnectorException) e).getErrorDetails().errorCode());
            }
        }
    }

    @Test
    public void testGetTableSchema_ResourceNotFound_NoCaseMatch()
    {
        ResourceNotFoundException notFound = (ResourceNotFoundException) ResourceNotFoundException.builder()
                .message("Table: nonexistent_table not found")
                .statusCode(400)
                .build();

        when(ddbClient.scan(any(ScanRequest.class))).thenThrow(notFound);
        when(ddbClient.listTables(any(ListTablesRequest.class)))
                .thenReturn(ListTablesResponse.builder().tableNames(Collections.emptyList()).build());

        try {
            tableResolver.getTableSchema("nonexistent_table");
            fail("Expected AthenaConnectorException");
        }
        catch (Exception e) {
            assertTrue(e instanceof AthenaConnectorException);
            assertEquals(FederationSourceErrorCode.ENTITY_NOT_FOUND_EXCEPTION.toString(),
                    ((AthenaConnectorException) e).getErrorDetails().errorCode());
        }
    }

    @Test
    public void testHandleDynamoDBException_GenericRuntimeException()
    {
        RuntimeException generic = new RuntimeException("Something unexpected");
        AthenaConnectorException result = DDBTableUtils.handleDynamoDBException(generic);
        assertEquals(FederationSourceErrorCode.INTERNAL_SERVICE_EXCEPTION.toString(), result.getErrorDetails().errorCode());
    }
}
