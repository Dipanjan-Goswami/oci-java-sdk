/**
 * Copyright (c) 2016, 2017, Oracle and/or its affiliates. All rights reserved.
 */
package com.oracle.bmc.objectstorage.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.model.MultipartUpload;
import com.oracle.bmc.objectstorage.model.MultipartUploadPartSummary;
import com.oracle.bmc.objectstorage.requests.AbortMultipartUploadRequest;
import com.oracle.bmc.objectstorage.requests.CommitMultipartUploadRequest;
import com.oracle.bmc.objectstorage.requests.CreateMultipartUploadRequest;
import com.oracle.bmc.objectstorage.requests.ListMultipartUploadPartsRequest;
import com.oracle.bmc.objectstorage.requests.ListMultipartUploadsRequest;
import com.oracle.bmc.objectstorage.requests.UploadPartRequest;
import com.oracle.bmc.objectstorage.responses.AbortMultipartUploadResponse;
import com.oracle.bmc.objectstorage.responses.CommitMultipartUploadResponse;
import com.oracle.bmc.objectstorage.responses.CreateMultipartUploadResponse;
import com.oracle.bmc.objectstorage.responses.ListMultipartUploadPartsResponse;
import com.oracle.bmc.objectstorage.responses.ListMultipartUploadsResponse;
import com.oracle.bmc.objectstorage.responses.UploadPartResponse;
import com.oracle.bmc.util.StreamUtils;

public class MultipartObjectAssemblerTest {
    private static final String NAMESPACE = "namespace";
    private static final String BUCKET = "bucket";
    private static final String OBJECT = "object";
    private static final String CONTENT_TYPE = "json";
    private static final String CONTENT_LANGUAGE = "en";
    private static final String CONTENT_ENCODING = "gzip";
    private static final Map<String, String> OPC_META = new HashMap<>();
    private static final boolean ALLOW_OVERWRITE = false;

    private ExecutorService executorService;
    private MultipartObjectAssembler assembler;

    @Mock private ObjectStorage service;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        executorService = Executors.newSingleThreadExecutor();
        assembler =
                new MultipartObjectAssembler(
                        service, NAMESPACE, BUCKET, OBJECT, ALLOW_OVERWRITE, executorService);
    }

    @After
    public void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    public void newRequest() {
        String uploadId = "uploadId";

        initializeCreateMultipartUpload(uploadId);

        MultipartManifest manifest =
                assembler.newRequest(CONTENT_TYPE, CONTENT_LANGUAGE, CONTENT_ENCODING, OPC_META);
        assertNotNull(manifest);
        assertEquals(uploadId, manifest.getUploadId());

        ArgumentCaptor<CreateMultipartUploadRequest> captor =
                ArgumentCaptor.forClass(CreateMultipartUploadRequest.class);
        verify(service).createMultipartUpload(captor.capture());

        CreateMultipartUploadRequest request = captor.getValue();
        assertEquals(NAMESPACE, request.getNamespaceName());
        assertEquals(BUCKET, request.getBucketName());
        assertEquals(OBJECT, request.getCreateMultipartUploadDetails().getObject());
        assertEquals(CONTENT_TYPE, request.getCreateMultipartUploadDetails().getContentType());
        assertEquals(
                CONTENT_LANGUAGE, request.getCreateMultipartUploadDetails().getContentLanguage());
        assertEquals(
                CONTENT_ENCODING, request.getCreateMultipartUploadDetails().getContentEncoding());
        assertEquals(OPC_META, request.getCreateMultipartUploadDetails().getMetadata());

        // test trying reuse the assembler
        try {
            assembler.newRequest(CONTENT_TYPE, CONTENT_LANGUAGE, CONTENT_ENCODING, OPC_META);
            fail("Should have thrown IllegalStateException");
        } catch (IllegalStateException e) {
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void resumeUpload_noMatchingUploadId() {
        ArrayList<MultipartUpload> existingUploads = new ArrayList<>();
        existingUploads.add(MultipartUpload.builder().uploadId("foobar").build());
        ListMultipartUploadsResponse listResponse1 =
                ListMultipartUploadsResponse.builder()
                        .opcNextPage("nextPage")
                        .items(existingUploads)
                        .build();
        ListMultipartUploadsResponse listResponse2 =
                ListMultipartUploadsResponse.builder()
                        .items(new ArrayList<MultipartUpload>())
                        .build();
        when(service.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                .thenReturn(listResponse1)
                .thenReturn(listResponse2);

        assembler.resumeRequest("doesNotExist");
    }

    @Test
    public void resumeUpload() {
        String pageToken = "nextPage";
        String uploadId = "exists";

        ArrayList<MultipartUpload> existingUploads = new ArrayList<>();
        existingUploads.add(MultipartUpload.builder().uploadId(uploadId).build());
        // empty first page, result found on second page
        ListMultipartUploadsResponse listResponse1 =
                ListMultipartUploadsResponse.builder()
                        .opcNextPage(pageToken)
                        .items(new ArrayList<MultipartUpload>())
                        .build();
        ListMultipartUploadsResponse listResponse2 =
                ListMultipartUploadsResponse.builder().items(existingUploads).build();
        when(service.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                .thenReturn(listResponse1)
                .thenReturn(listResponse2);

        ArrayList<MultipartUploadPartSummary> parts1 = new ArrayList<>();
        parts1.add(MultipartUploadPartSummary.builder().etag("etag1").partNumber(1).build());
        ArrayList<MultipartUploadPartSummary> parts2 = new ArrayList<>();
        parts2.add(MultipartUploadPartSummary.builder().etag("etag3").partNumber(3).build());

        ListMultipartUploadPartsResponse partsResponse1 =
                ListMultipartUploadPartsResponse.builder()
                        .opcNextPage(pageToken)
                        .items(parts1)
                        .build();
        ListMultipartUploadPartsResponse partsResponse2 =
                ListMultipartUploadPartsResponse.builder().items(parts2).build();
        when(service.listMultipartUploadParts(any(ListMultipartUploadPartsRequest.class)))
                .thenReturn(partsResponse1)
                .thenReturn(partsResponse2);

        MultipartManifest manifest = assembler.resumeRequest(uploadId);
        assertNotNull(manifest);
        assertEquals(uploadId, manifest.getUploadId());
        assertEquals(2, manifest.listCompletedParts().size());

        ArgumentCaptor<ListMultipartUploadsRequest> listUploadsCaptor =
                ArgumentCaptor.forClass(ListMultipartUploadsRequest.class);
        verify(service, times(2)).listMultipartUploads(listUploadsCaptor.capture());
        ListMultipartUploadsRequest listUploadsRequest1 = listUploadsCaptor.getAllValues().get(0);
        assertEquals(NAMESPACE, listUploadsRequest1.getNamespaceName());
        assertEquals(BUCKET, listUploadsRequest1.getBucketName());
        assertEquals(100, listUploadsRequest1.getLimit().intValue());
        assertNull(listUploadsRequest1.getPage());
        ListMultipartUploadsRequest listUploadsRequest2 = listUploadsCaptor.getAllValues().get(1);
        assertEquals(NAMESPACE, listUploadsRequest2.getNamespaceName());
        assertEquals(BUCKET, listUploadsRequest2.getBucketName());
        assertEquals(100, listUploadsRequest2.getLimit().intValue());
        assertEquals(pageToken, listUploadsRequest2.getPage());

        ArgumentCaptor<ListMultipartUploadPartsRequest> listPartsCaptor =
                ArgumentCaptor.forClass(ListMultipartUploadPartsRequest.class);
        verify(service, times(2)).listMultipartUploadParts(listPartsCaptor.capture());
        ListMultipartUploadPartsRequest listPartsRequest1 = listPartsCaptor.getAllValues().get(0);
        assertEquals(NAMESPACE, listPartsRequest1.getNamespaceName());
        assertEquals(BUCKET, listPartsRequest1.getBucketName());
        assertEquals(uploadId, listPartsRequest1.getUploadId());
        assertEquals(100, listPartsRequest1.getLimit().intValue());
        assertNull(listPartsRequest1.getPage());
        ListMultipartUploadPartsRequest listPartsRequest2 = listPartsCaptor.getAllValues().get(1);
        assertEquals(NAMESPACE, listPartsRequest2.getNamespaceName());
        assertEquals(BUCKET, listPartsRequest2.getBucketName());
        assertEquals(uploadId, listPartsRequest2.getUploadId());
        assertEquals(100, listPartsRequest2.getLimit().intValue());
        assertEquals(pageToken, listPartsRequest2.getPage());

        // test trying reuse the assembler
        try {
            assembler.resumeRequest(uploadId);
            fail("Should have thrown IllegalStateException");
        } catch (IllegalStateException e) {
        }
    }

    @Test
    public void addParts_allSuccessful_commit() throws Exception {
        String uploadId = "uploadId";
        initializeCreateMultipartUpload(uploadId);
        MultipartManifest manifest =
                assembler.newRequest(CONTENT_TYPE, CONTENT_LANGUAGE, CONTENT_ENCODING, OPC_META);

        byte[] bytes = "abcd".getBytes();

        File file = File.createTempFile("unitTest", ".txt");
        file.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
        }

        String etag1 = "etag1";
        String etag2 = "etag2";
        UploadPartResponse uploadPartResponse1 = UploadPartResponse.builder().eTag(etag1).build();
        UploadPartResponse uploadPartResponse2 = UploadPartResponse.builder().eTag(etag2).build();
        when(service.uploadPart(any(UploadPartRequest.class)))
                .thenReturn(uploadPartResponse1)
                .thenReturn(uploadPartResponse2);

        CommitMultipartUploadResponse finalCommitResponse =
                CommitMultipartUploadResponse.builder().build();
        when(service.commitMultipartUpload(any(CommitMultipartUploadRequest.class)))
                .thenReturn(finalCommitResponse);

        String md5_1 = "md5_1";
        String md5_2 = "md5_2";

        assembler.addPart(file, md5_1);
        assembler.addPart(StreamUtils.createByteArrayInputStream(bytes), bytes.length, md5_2);

        CommitMultipartUploadResponse commitResponse = assembler.commit();
        assertSame(finalCommitResponse, commitResponse);

        assertTrue(manifest.isUploadComplete());
        assertTrue(manifest.isUploadSuccessful());
        assertEquals(2, manifest.listCompletedParts().size());
        assertEquals(1, manifest.listCompletedParts().get(0).getPartNum().intValue());
        assertEquals(etag1, manifest.listCompletedParts().get(0).getEtag());
        assertEquals(2, manifest.listCompletedParts().get(1).getPartNum().intValue());
        assertEquals(etag2, manifest.listCompletedParts().get(1).getEtag());

        ArgumentCaptor<UploadPartRequest> uploadCaptor =
                ArgumentCaptor.forClass(UploadPartRequest.class);
        verify(service, times(2)).uploadPart(uploadCaptor.capture());
        verifyUploadPart(uploadCaptor.getAllValues().get(0), uploadId, 1, md5_1);
        verifyUploadPart(uploadCaptor.getAllValues().get(1), uploadId, 2, md5_2);

        file.delete();
    }

    @Test
    public void addParts_someFailed_commitFailure() throws Exception {
        String uploadId = "uploadId";
        initializeCreateMultipartUpload(uploadId);
        MultipartManifest manifest =
                assembler.newRequest(CONTENT_TYPE, CONTENT_LANGUAGE, CONTENT_ENCODING, OPC_META);

        byte[] bytes = "abcd".getBytes();

        File file = File.createTempFile("unitTest", ".txt");
        file.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
        }

        String etag1 = "etag1";
        UploadPartResponse uploadPartResponse1 = UploadPartResponse.builder().eTag(etag1).build();
        when(service.uploadPart(any(UploadPartRequest.class)))
                .thenReturn(uploadPartResponse1)
                .thenThrow(new RuntimeException());

        String md5_1 = "md5_1";
        String md5_2 = "md5_2";

        assembler.addPart(file, md5_1);
        assembler.addPart(StreamUtils.createByteArrayInputStream(bytes), bytes.length, md5_2);

        try {
            assembler.commit();
            fail("Should have thrown IllegalStateException");
        } catch (IllegalStateException e) {
        }

        assertTrue(manifest.isUploadComplete());
        assertFalse(manifest.isUploadSuccessful());
        assertEquals(1, manifest.listCompletedParts().size());
        assertEquals(1, manifest.listCompletedParts().get(0).getPartNum().intValue());
        assertEquals(etag1, manifest.listCompletedParts().get(0).getEtag());
        assertEquals(2, manifest.listFailedParts().get(0).intValue());

        ArgumentCaptor<UploadPartRequest> uploadCaptor =
                ArgumentCaptor.forClass(UploadPartRequest.class);
        verify(service, times(2)).uploadPart(uploadCaptor.capture());
        verifyUploadPart(uploadCaptor.getAllValues().get(0), uploadId, 1, md5_1);
        verifyUploadPart(uploadCaptor.getAllValues().get(1), uploadId, 2, md5_2);

        file.delete();
    }

    @Test
    public void commit_addPart_afterAbort() throws Exception {
        String uploadId = "uploadId";
        initializeCreateMultipartUpload(uploadId);
        assembler.newRequest(CONTENT_TYPE, CONTENT_LANGUAGE, CONTENT_ENCODING, OPC_META);
        assembler.abort();

        try {
            assembler.addPart(StreamUtils.createByteArrayInputStream("a".getBytes()), 1, null);
            fail("Should have thrown IllegalStateException");
        } catch (IllegalStateException e) {
        }

        try {
            assembler.commit();
            fail("Should have thrown IllegalStateException");
        } catch (IllegalStateException e) {
        }
    }

    private void verifyUploadPart(
            UploadPartRequest request, String uploadId, int partNum, String md5) {
        assertEquals(NAMESPACE, request.getNamespaceName());
        assertEquals(BUCKET, request.getBucketName());
        assertEquals(uploadId, request.getUploadId());
        assertEquals(partNum, request.getUploadPartNum().intValue());
        assertEquals(md5, request.getContentMD5());
        assertEquals("*", request.getIfNoneMatch());
        assertNotNull(request.getUploadPartBody());
    }

    @Test
    public void abort() {
        String uploadId = "uploadId";
        initializeCreateMultipartUpload(uploadId);
        MultipartManifest manifest =
                assembler.newRequest(CONTENT_TYPE, CONTENT_LANGUAGE, CONTENT_ENCODING, OPC_META);

        when(service.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                .thenReturn(AbortMultipartUploadResponse.builder().build());

        assertFalse(manifest.isUploadAborted());
        assertNotNull(assembler.abort());
        assertTrue(manifest.isUploadAborted());

        ArgumentCaptor<AbortMultipartUploadRequest> captor =
                ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
        verify(service).abortMultipartUpload(captor.capture());

        AbortMultipartUploadRequest request = captor.getValue();
        assertEquals(NAMESPACE, request.getNamespaceName());
        assertEquals(BUCKET, request.getBucketName());
        assertEquals(uploadId, request.getUploadId());
    }

    private void initializeCreateMultipartUpload(String uploadId) {
        CreateMultipartUploadResponse response =
                CreateMultipartUploadResponse.builder()
                        .multipartUpload(MultipartUpload.builder().uploadId(uploadId).build())
                        .build();
        when(service.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(response);
    }
}
