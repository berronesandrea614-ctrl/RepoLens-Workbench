package com.repolens.service;

import com.repolens.common.exception.BizException;
import com.repolens.service.impl.MilvusServiceImpl;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MilvusServiceImplTest {

    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private MilvusClientV2 milvusClient;

    @Test
    void ensureCollection_shouldValidateExistingDimension() {
        MilvusServiceImpl service = new MilvusServiceImpl(embeddingService);
        ReflectionTestUtils.setField(service, "collectionName", "code_chunk_collection");
        ReflectionTestUtils.setField(service, "embeddingDimension", 384);
        ReflectionTestUtils.setField(service, "milvusClient", milvusClient);

        when(milvusClient.hasCollection(any())).thenReturn(true);
        when(milvusClient.describeCollection(any())).thenReturn(buildDescribeResponse(384));
        doNothing().when(milvusClient).loadCollection(any());

        service.ensureCollection();

        verify(milvusClient).describeCollection(any());
        verify(milvusClient).loadCollection(any());
    }

    @Test
    void ensureCollection_shouldThrowWhenDimensionMismatch() {
        MilvusServiceImpl service = new MilvusServiceImpl(embeddingService);
        ReflectionTestUtils.setField(service, "collectionName", "code_chunk_collection");
        ReflectionTestUtils.setField(service, "embeddingDimension", 384);
        ReflectionTestUtils.setField(service, "milvusClient", milvusClient);

        when(milvusClient.hasCollection(any())).thenReturn(true);
        when(milvusClient.describeCollection(any())).thenReturn(buildDescribeResponse(1024));

        BizException ex = Assertions.assertThrows(BizException.class, service::ensureCollection);

        Assertions.assertTrue(ex.getMessage().contains("dimension mismatch"));
    }

    private DescribeCollectionResp buildDescribeResponse(int dimension) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(false)
                .build();
        schema.addField(AddFieldReq.builder()
                .fieldName("embedding")
                .dataType(DataType.FloatVector)
                .dimension(dimension)
                .build());

        DescribeCollectionResp response = DescribeCollectionResp.builder().build();
        response.setCollectionSchema(schema);
        return response;
    }
}
