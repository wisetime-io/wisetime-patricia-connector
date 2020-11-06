package io.wisetime.connector.patricia;

import static io.wisetime.connector.patricia.PatriciaConnector.PATRICIA_WORK_CODES_HASH_KEY;
import static io.wisetime.connector.patricia.PatriciaConnector.PATRICIA_WORK_CODES_LAST_SYNC_KEY;
import static io.wisetime.connector.patricia.PatriciaConnector.WORK_CODES_BATCH_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.github.javafaker.Faker;
import com.google.inject.Guice;
import io.wisetime.connector.ConnectorModule;
import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.patricia.PatriciaDao.WorkCode;
import io.wisetime.connector.patricia.util.HashFunction;
import io.wisetime.generated.connect.ActivityType;
import io.wisetime.generated.connect.SyncActivityTypesRequest;
import io.wisetime.generated.connect.SyncSession;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * @author yehor.lashkul
 */
class PatriciaConnectorActivityTypeUpdateTest {

  private static final Faker FAKER = new Faker();

  private static RandomDataGenerator randomDataGenerator = new RandomDataGenerator();
  private static HashFunction hashFunctionSpy = spy(HashFunction.class);
  private static PatriciaDao patriciaDaoMock = mock(PatriciaDao.class);
  private static ApiClient apiClientMock = mock(ApiClient.class);
  private static ConnectorStore connectorStoreMock = mock(ConnectorStore.class);

  private static PatriciaConnector connector;

  @BeforeAll
  static void setUp() {
    // Set a role type id to use
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.PATRICIA_ROLE_TYPE_ID, "4");

    connector = Guice.createInjector(
        binder -> binder.bind(PatriciaDao.class).toInstance(patriciaDaoMock),
        binder -> binder.bind(HashFunction.class).toInstance(hashFunctionSpy)
    )
        .getInstance(PatriciaConnector.class);

    // Ensure PatriciaConnector#init will not fail
    doReturn(true).when(patriciaDaoMock).hasExpectedSchema();

    connector.init(new ConnectorModule(apiClientMock, connectorStoreMock, 5, 15));
  }

  @BeforeEach
  void setUpTest() {
    reset(patriciaDaoMock);
    reset(apiClientMock);
    reset(connectorStoreMock);
    reset(hashFunctionSpy);
  }

  @Test
  void performActivityTypeUpdate_noWorkCodes() throws Exception {
    final String syncSessionId = FAKER.numerify("syncSession-###");
    when(patriciaDaoMock.findWorkCodes(anyInt(), anyInt())).thenReturn(List.of());
    when(apiClientMock.activityTypesStartSyncSession())
        .thenReturn(new SyncSession().syncSessionId(syncSessionId));

    connector.performActivityTypeUpdate();

    // check that session was started and completed
    // as we should sync even with empty work codes to handle deletions
    verify(apiClientMock, times(1)).activityTypesStartSyncSession();
    verify(apiClientMock, times(1)).activityTypesCompleteSyncSession(new SyncSession()
        .syncSessionId(syncSessionId));
    // check that no activity type was sent
    verify(apiClientMock, never()).syncActivityTypes(any());
  }

  @Test
  void performActivityTypeUpdate_sameHash_syncedLessThanDayAgo() throws Exception {
    // current and previous hashes are the same
    final String hash = FAKER.numerify("hash-###");
    when(hashFunctionSpy.hashStrings(anyList()))
        .thenReturn(hash);
    when(connectorStoreMock.getString(PATRICIA_WORK_CODES_HASH_KEY))
        .thenReturn(Optional.of(hash));

    // last synced recently
    when(connectorStoreMock.getLong(PATRICIA_WORK_CODES_LAST_SYNC_KEY))
        .thenReturn(Optional.of(System.currentTimeMillis()));

    connector.performActivityTypeUpdate();

    // check that there were no api calls
    verifyZeroInteractions(apiClientMock);
    // last sync timestamp should not be saved as no sync happen
    verify(connectorStoreMock, never()).putLong(eq(PATRICIA_WORK_CODES_LAST_SYNC_KEY), anyLong());
    // new hash should not be saved as it wasn't changed
    verify(connectorStoreMock, never()).putString(eq(PATRICIA_WORK_CODES_HASH_KEY), anyString());
  }

  @Test
  void performActivityTypeUpdate_sameHash_syncedMoreThanDayAgo() throws Exception {
    // current and previous hashes are the same
    final String hash = FAKER.numerify("hash-###");
    when(hashFunctionSpy.hashStrings(anyList()))
        .thenReturn(hash);
    when(connectorStoreMock.getString(PATRICIA_WORK_CODES_HASH_KEY))
        .thenReturn(Optional.of(hash));

    // last synced more than day ago
    when(connectorStoreMock.getLong(PATRICIA_WORK_CODES_LAST_SYNC_KEY))
        .thenReturn(Optional.of(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)));

    when(apiClientMock.activityTypesStartSyncSession())
        .thenReturn(new SyncSession().syncSessionId(FAKER.numerify("syncSession-###")));
    when(patriciaDaoMock.findWorkCodes(anyInt(), anyInt()))
        .thenReturn(List.of(randomDataGenerator.randomWorkCode()));

    connector.performActivityTypeUpdate();

    // check that sync session was processed
    verify(apiClientMock, times(1)).activityTypesStartSyncSession();
    verify(apiClientMock, times(1)).activityTypesCompleteSyncSession(any());
    verify(apiClientMock, times(1)).syncActivityTypes(any());
  }

  @Test
  void performActivityTypeUpdate_hashDiffers() throws Exception {
    // current and previous hashes are different
    when(hashFunctionSpy.hashStrings(anyList()))
        .thenReturn(FAKER.numerify("hash-#####"));
    when(connectorStoreMock.getString(PATRICIA_WORK_CODES_HASH_KEY))
        .thenReturn(Optional.of(FAKER.numerify("hash-#####")));

    // last synced recently
    when(connectorStoreMock.getLong(PATRICIA_WORK_CODES_LAST_SYNC_KEY))
        .thenReturn(Optional.of(System.currentTimeMillis()));

    when(apiClientMock.activityTypesStartSyncSession())
        .thenReturn(new SyncSession().syncSessionId(FAKER.numerify("syncSession-###")));
    when(patriciaDaoMock.findWorkCodes(anyInt(), anyInt()))
        .thenReturn(List.of(randomDataGenerator.randomWorkCode()));

    connector.performActivityTypeUpdate();

    // check that sync session was processed
    verify(apiClientMock, times(1)).activityTypesStartSyncSession();
    verify(apiClientMock, times(1)).activityTypesCompleteSyncSession(any());
    verify(apiClientMock, times(1)).syncActivityTypes(any());
  }

  @Test
  void performActivityTypeUpdate() throws Exception {
    final String newHash = FAKER.numerify("hash-###");
    when(hashFunctionSpy.hashStrings(anyList()))
        .thenReturn(newHash);

    final String syncSessionId = FAKER.numerify("syncSession-###");
    when(apiClientMock.activityTypesStartSyncSession())
        .thenReturn(new SyncSession().syncSessionId(syncSessionId));

    final WorkCode workCode1 = randomDataGenerator.randomWorkCode();
    final WorkCode workCode2 = randomDataGenerator.randomWorkCode();
    when(patriciaDaoMock.findWorkCodes(anyInt(), anyInt()))
        .thenReturn(List.of(workCode1, workCode2));

    connector.performActivityTypeUpdate();

    final InOrder inOrder = Mockito.inOrder(apiClientMock);
    // check that session was started
    inOrder.verify(apiClientMock, times(1)).activityTypesStartSyncSession();
    // check that proper request has been sent with sync session id
    final SyncActivityTypesRequest expectedRequest = new SyncActivityTypesRequest()
        .syncSessionId(syncSessionId)
        .activityTypes(List.of(
            new ActivityType()
                .code(String.valueOf(workCode1.workCodeId()))
                .label(workCode1.workCodeText()),
            new ActivityType()
                .code(String.valueOf(workCode2.workCodeId()))
                .label(workCode2.workCodeText())));
    inOrder.verify(apiClientMock, times(1)).syncActivityTypes(expectedRequest);
    // check that session was completed at the end
    inOrder.verify(apiClientMock, times(1)).activityTypesCompleteSyncSession(new SyncSession()
        .syncSessionId(syncSessionId));

    // last sync timestamp should be saved
    final ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
    verify(connectorStoreMock, times(1)).putLong(eq(PATRICIA_WORK_CODES_LAST_SYNC_KEY), captor.capture());
    assertThat(captor.getValue())
        .as("new last sync timestamp should be saved")
        .isCloseTo(System.currentTimeMillis(), within(TimeUnit.SECONDS.toMillis(1)));
    // new hash should be saved
    verify(connectorStoreMock, times(1)).putString(PATRICIA_WORK_CODES_HASH_KEY, newHash);
  }

  @Test
  void performActivityTypeUpdate_multipleBatches() throws Exception {
    final String syncSessionId = FAKER.numerify("syncSession-###");
    when(apiClientMock.activityTypesStartSyncSession())
        .thenReturn(new SyncSession().syncSessionId(syncSessionId));

    final List<WorkCode> workCodesBatch2 = randomDataGenerator.randomWorkCodes(WORK_CODES_BATCH_SIZE);
    final List<WorkCode> workCodes2 = randomDataGenerator.randomWorkCodes(FAKER.random().nextInt(WORK_CODES_BATCH_SIZE));
    when(patriciaDaoMock.findWorkCodes(0, WORK_CODES_BATCH_SIZE))
        .thenReturn(workCodesBatch2);
    when(patriciaDaoMock.findWorkCodes(WORK_CODES_BATCH_SIZE, WORK_CODES_BATCH_SIZE))
        .thenReturn(workCodes2);

    connector.performActivityTypeUpdate();

    final InOrder inOrder = Mockito.inOrder(apiClientMock);
    // check that session was started
    inOrder.verify(apiClientMock, times(1)).activityTypesStartSyncSession();
    // check that 2 batches has been sent
    final ArgumentCaptor<SyncActivityTypesRequest> captor = ArgumentCaptor.forClass(SyncActivityTypesRequest.class);
    inOrder.verify(apiClientMock, times(2)).syncActivityTypes(captor.capture());
    assertThat(captor.getAllValues().get(0).getActivityTypes())
        .hasSize(workCodesBatch2.size());
    assertThat(captor.getAllValues().get(1).getActivityTypes())
        .hasSize(workCodes2.size());
    // check that session was completed at the end
    inOrder.verify(apiClientMock, times(1)).activityTypesCompleteSyncSession(new SyncSession()
        .syncSessionId(syncSessionId));
  }

}
