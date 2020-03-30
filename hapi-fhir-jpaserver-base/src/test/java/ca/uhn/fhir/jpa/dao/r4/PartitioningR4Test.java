package ca.uhn.fhir.jpa.dao.r4;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.dao.DaoConfig;
import ca.uhn.fhir.jpa.model.entity.*;
import ca.uhn.fhir.jpa.searchparam.SearchParamConstants;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.util.TestUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.hamcrest.Matchers;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.SearchParameter;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.ServletException;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
public class PartitioningR4Test extends BaseJpaR4SystemTest {

	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(PartitioningR4Test.class);
	private MyInterceptor myTenantInterceptor;
	private LocalDate myTenantDate;
	private int myPartitionId;

	@After
	public void after() {
		myTenantInterceptor.assertNoRemainingIds();

		myDaoConfig.setPartitioningEnabled(new DaoConfig().isPartitioningEnabled());

		myInterceptorRegistry.unregisterInterceptorsIf(t -> t instanceof MyInterceptor);
		myInterceptor = null;
	}

	@Override
	@Before
	public void before() throws ServletException {
		super.before();

		myDaoConfig.setPartitioningEnabled(true);
		myDaoConfig.setUniqueIndexesEnabled(true);
		myModelConfig.setDefaultSearchParamsCanBeOverridden(true);

		myTenantDate = LocalDate.of(2020, Month.JANUARY, 14);
		myPartitionId = 3;

		myTenantInterceptor = new MyInterceptor();
	}


	@Test
	public void testCreateResourceNoTenant() {
		Patient p = new Patient();
		p.addIdentifier().setSystem("system").setValue("value");
		p.setBirthDate(new Date());
		Long patientId = myPatientDao.create(p).getId().getIdPartAsLong();

		runInTransaction(() -> {
			ResourceTable resourceTable = myResourceTableDao.findById(patientId).orElseThrow(IllegalArgumentException::new);
			assertNull(resourceTable.getPartitionId());
		});
	}


	@Test
	public void testCreateResourceWithTenant() {
		createUniqueCompositeSp();
		createRequestId();

		addCreateTenant(myPartitionId, myTenantDate);
		addCreateTenant(myPartitionId, myTenantDate);

		Organization org = new Organization();
		org.setName("org");
		IIdType orgId = myOrganizationDao.create(org).getId().toUnqualifiedVersionless();

		Patient p = new Patient();
		p.getMeta().addTag("http://system", "code", "diisplay");
		p.addName().setFamily("FAM");
		p.addIdentifier().setSystem("system").setValue("value");
		p.setBirthDate(new Date());
		p.getManagingOrganization().setReferenceElement(orgId);
		Long patientId = myPatientDao.create(p, mySrd).getId().getIdPartAsLong();

		runInTransaction(() -> {
			// HFJ_RESOURCE
			ResourceTable resourceTable = myResourceTableDao.findById(patientId).orElseThrow(IllegalArgumentException::new);
			assertEquals(myPartitionId, resourceTable.getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, resourceTable.getPartitionId().getPartitionDate());

			// HFJ_RES_TAG
			List<ResourceTag> tags = myResourceTagDao.findAll();
			assertEquals(1, tags.size());
			assertEquals(myPartitionId, tags.get(0).getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, tags.get(0).getPartitionId().getPartitionDate());

			// HFJ_RES_VER
			ResourceHistoryTable version = myResourceHistoryTableDao.findForIdAndVersionAndFetchProvenance(patientId, 1L);
			assertEquals(myPartitionId, version.getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, version.getPartitionId().getPartitionDate());

			// HFJ_HISTORY_TAG
			List<ResourceHistoryTag> historyTags = myResourceHistoryTagDao.findAll();
			assertEquals(1, historyTags.size());
			assertEquals(myPartitionId, historyTags.get(0).getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, historyTags.get(0).getPartitionId().getPartitionDate());

			// HFJ_RES_VER_PROV
			assertNotNull(version.getProvenance());
			assertEquals(myPartitionId, version.getProvenance().getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, version.getProvenance().getPartitionId().getPartitionDate());

			// HFJ_SPIDX_STRING
			List<ResourceIndexedSearchParamString> strings = myResourceIndexedSearchParamStringDao.findAllForResourceId(patientId);
			ourLog.info("\n * {}", strings.stream().map(ResourceIndexedSearchParamString::toString).collect(Collectors.joining("\n * ")));
			assertEquals(10, strings.size());
			assertEquals(myPartitionId, strings.get(0).getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, strings.get(0).getPartitionId().getPartitionDate());

			// HFJ_SPIDX_DATE
			List<ResourceIndexedSearchParamDate> dates = myResourceIndexedSearchParamDateDao.findAllForResourceId(patientId);
			ourLog.info("\n * {}", dates.stream().map(ResourceIndexedSearchParamDate::toString).collect(Collectors.joining("\n * ")));
			assertEquals(2, dates.size());
			assertEquals(myPartitionId, dates.get(0).getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, dates.get(0).getPartitionId().getPartitionDate());
			assertEquals(myPartitionId, dates.get(1).getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, dates.get(1).getPartitionId().getPartitionDate());

			// HFJ_RES_LINK
			List<ResourceLink> resourceLinks = myResourceLinkDao.findAllForResourceId(patientId);
			assertEquals(1, resourceLinks.size());
			assertEquals(myPartitionId, resourceLinks.get(0).getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, resourceLinks.get(0).getPartitionId().getPartitionDate());

			// HFJ_RES_PARAM_PRESENT
			List<SearchParamPresent> presents = mySearchParamPresentDao.findAllForResource(resourceTable);
			assertEquals(myPartitionId, presents.size());
			assertEquals(myPartitionId, presents.get(0).getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, presents.get(0).getPartitionId().getPartitionDate());

			// HFJ_IDX_CMP_STRING_UNIQ
			List<ResourceIndexedCompositeStringUnique> uniques = myResourceIndexedCompositeStringUniqueDao.findAllForResourceId(patientId);
			assertEquals(1, uniques.size());
			assertEquals(myPartitionId, uniques.get(0).getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, uniques.get(0).getPartitionId().getPartitionDate());
		});

	}

	@Test
	public void testCreateWithForcedId() {
		addCreateTenant(myPartitionId, myTenantDate);
		addCreateTenant(myPartitionId, myTenantDate);

		Organization org = new Organization();
		org.setId("org");
		org.setName("org");
		IIdType orgId = myOrganizationDao.update(org).getId().toUnqualifiedVersionless();

		Patient p = new Patient();
		p.setId("pat");
		p.getManagingOrganization().setReferenceElement(orgId);
		myPatientDao.update(p, mySrd);

		runInTransaction(() -> {
			// HFJ_FORCED_ID
			List<ForcedId> forcedIds = myForcedIdDao.findAll();
			assertEquals(2, forcedIds.size());
			assertEquals(myPartitionId, forcedIds.get(0).getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, forcedIds.get(0).getPartitionId().getPartitionDate());
			assertEquals(myPartitionId, forcedIds.get(1).getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, forcedIds.get(1).getPartitionId().getPartitionDate());
		});

	}

	@Test
	public void testUpdateResourceWithTenant() {
		createRequestId();
		addCreateTenant(3, LocalDate.of(2020, Month.JANUARY, 14));
		addCreateTenant(3, LocalDate.of(2020, Month.JANUARY, 14));

		// Create a resource
		Patient p = new Patient();
		p.getMeta().addTag("http://system", "code", "diisplay");
		p.setActive(true);
		Long patientId = myPatientDao.create(p).getId().getIdPartAsLong();
		runInTransaction(() -> {
			// HFJ_RESOURCE
			ResourceTable resourceTable = myResourceTableDao.findById(patientId).orElseThrow(IllegalArgumentException::new);
			assertEquals(myPartitionId, resourceTable.getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, resourceTable.getPartitionId().getPartitionDate());
		});

		// Update that resource
		p = new Patient();
		p.setId("Patient/" + patientId);
		p.setActive(false);
		myPatientDao.update(p, mySrd);

		runInTransaction(() -> {
			// HFJ_RESOURCE
			ResourceTable resourceTable = myResourceTableDao.findById(patientId).orElseThrow(IllegalArgumentException::new);
			assertEquals(myPartitionId, resourceTable.getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, resourceTable.getPartitionId().getPartitionDate());

			// HFJ_RES_VER
			int version = 2;
			ResourceHistoryTable resVer = myResourceHistoryTableDao.findForIdAndVersionAndFetchProvenance(patientId, version);
			assertEquals(myPartitionId, resVer.getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, resVer.getPartitionId().getPartitionDate());

			// HFJ_HISTORY_TAG
			List<ResourceHistoryTag> historyTags = myResourceHistoryTagDao.findAll();
			assertEquals(2, historyTags.size());
			assertEquals(myPartitionId, historyTags.get(0).getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, historyTags.get(0).getPartitionId().getPartitionDate());
			assertEquals(myPartitionId, historyTags.get(1).getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, historyTags.get(1).getPartitionId().getPartitionDate());

			// HFJ_RES_VER_PROV
			assertNotNull(resVer.getProvenance());
			assertNotNull(resVer.getPartitionId());
			assertEquals(myPartitionId, resVer.getProvenance().getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, resVer.getProvenance().getPartitionId().getPartitionDate());

			// HFJ_SPIDX_STRING
			List<ResourceIndexedSearchParamString> strings = myResourceIndexedSearchParamStringDao.findAllForResourceId(patientId);
			ourLog.info("\n * {}", strings.stream().map(ResourceIndexedSearchParamString::toString).collect(Collectors.joining("\n * ")));
			assertEquals(10, strings.size());
			assertEquals(myPartitionId, strings.get(0).getPartitionId().getPartitionId().intValue());
			assertEquals(myTenantDate, strings.get(0).getPartitionId().getPartitionDate());

		});

	}

	@Test
	public void testReadAcrossTenants() {
		IIdType patientId1 = createPatient(1, withActiveTrue());
		IIdType patientId2 = createPatient(2, withActiveTrue());

		addReadTenant(null);
		IdType gotId1 = myPatientDao.read(patientId1, mySrd).getIdElement().toUnqualifiedVersionless();
		assertEquals(patientId1, gotId1);

		addReadTenant(null);
		IdType gotId2 = myPatientDao.read(patientId2, mySrd).getIdElement().toUnqualifiedVersionless();
		assertEquals(patientId2, gotId2);
	}

	@Test
	public void testReadSpecificTenant_PidId() {
		IIdType patientIdNull = createPatient(null, withActiveTrue());
		IIdType patientId1 = createPatient(1, withActiveTrue());
		IIdType patientId2 = createPatient(2, withActiveTrue());

		// Read in correct tenant
		addReadTenant(1);
		IdType gotId1 = myPatientDao.read(patientId1, mySrd).getIdElement().toUnqualifiedVersionless();
		assertEquals(patientId1, gotId1);

		// Read in null tenant
		addReadTenant(1);
		try {
			myPatientDao.read(patientIdNull, mySrd).getIdElement().toUnqualifiedVersionless();
			fail();
		} catch (ResourceNotFoundException e) {
			assertThat(e.getMessage(), matchesPattern("Resource Patient/[0-9]+ is not known"));
		}

		// Read in wrong tenant
		addReadTenant(1);
		try {
			myPatientDao.read(patientId2, mySrd).getIdElement().toUnqualifiedVersionless();
			fail();
		} catch (ResourceNotFoundException e) {
			assertThat(e.getMessage(), matchesPattern("Resource Patient/[0-9]+ is not known"));
		}
	}

	@Test
	public void testReadSpecificTenant_ForcedId() {
		IIdType patientIdNull = createPatient(null, withActiveTrue(), withId("NULL"));
		IIdType patientId1 = createPatient(1, withActiveTrue(), withId("ONE"));
		IIdType patientId2 = createPatient(2, withActiveTrue(), withId("TWO"));

		// Read in correct tenant
		addReadTenant(1);
		IdType gotId1 = myPatientDao.read(patientId1, mySrd).getIdElement().toUnqualifiedVersionless();
		assertEquals(patientId1, gotId1);

		// Read in null tenant
		addReadTenant(1);
		try {
			myPatientDao.read(patientIdNull, mySrd).getIdElement().toUnqualifiedVersionless();
			fail();
		} catch (ResourceNotFoundException e) {
			assertThat(e.getMessage(), matchesPattern("Resource Patient/[0-9]+ is not known"));
		}

		// Read in wrong tenant
		addReadTenant(1);
		try {
			myPatientDao.read(patientId2, mySrd).getIdElement().toUnqualifiedVersionless();
			fail();
		} catch (ResourceNotFoundException e) {
			assertThat(e.getMessage(), matchesPattern("Resource Patient/[0-9]+ is not known"));
		}
	}

	@Test
	public void testSearch_NoParams_SearchAllTenants() {
		IIdType patientIdNull = createPatient(null, withActiveTrue());
		IIdType patientId1 = createPatient(1, withActiveTrue());
		IIdType patientId2 = createPatient(2, withActiveTrue());

		addReadTenant(null);

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids, Matchers.contains(patientIdNull, patientId1, patientId2));

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(0, StringUtils.countMatches(searchSql, "PARTITION_ID"));
	}

	@Test
	public void testSearch_NoParams_SearchOneTenant() {
		createPatient(null, withActiveTrue());
		IIdType patientId1 = createPatient(1, withActiveTrue());
		createPatient(2, withActiveTrue());

		addReadTenant(1);

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids, Matchers.contains(patientId1));

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
	}

	@Test
	public void testSearch_StringParam_SearchAllTenants() {
		IIdType patientIdNull = createPatient(null, withFamily("FAMILY"));
		IIdType patientId1 = createPatient(1, withFamily("FAMILY"));
		IIdType patientId2 = createPatient(2, withFamily("FAMILY"));

		addReadTenant(null);

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_FAMILY, new StringParam("FAMILY"));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		assertThat(ids, Matchers.contains(patientIdNull, patientId1, patientId2));

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(0, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "SP_VALUE_NORMALIZED"));
	}

	@Test
	public void testSearch_StringParam_SearchOneTenant() {
		createPatient(null, withFamily("FAMILY"));
		IIdType patientId1 = createPatient(1, withFamily("FAMILY"));
		createPatient(2, withFamily("FAMILY"));

		addReadTenant(1);

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_FAMILY, new StringParam("FAMILY"));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids, Matchers.contains(patientId1));

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "SP_VALUE_NORMALIZED"));
	}

	@Test
	public void testSearch_UniqueParam_SearchAllTenants() {
		createUniqueCompositeSp();

		IIdType id = createPatient(1, withBirthdate("2020-01-01"));

		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateParam("2020-01-01"));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids, Matchers.contains(id));

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(0, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "IDX_STRING='Patient?birthdate=2020-01-01'"));
	}


	@Test
	public void testSearch_UniqueParam_SearchOneTenant() {
		createUniqueCompositeSp();

		IIdType id = createPatient(1, withBirthdate("2020-01-01"));

		addReadTenant(1);
		myCaptureQueriesListener.clear();
		SearchParameterMap map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateParam("2020-01-01"));
		map.setLoadSynchronous(true);
		IBundleProvider results = myPatientDao.search(map);
		List<IIdType> ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids, Matchers.contains(id));

		String searchSql = myCaptureQueriesListener.getSelectQueriesForCurrentThread().get(0).getSql(true, true);
		ourLog.info("Search SQL:\n{}", searchSql);
		assertEquals(1, StringUtils.countMatches(searchSql, "PARTITION_ID"));
		assertEquals(1, StringUtils.countMatches(searchSql, "IDX_STRING='Patient?birthdate=2020-01-01'"));

		// Same query, different partition
		addReadTenant(2);
		myCaptureQueriesListener.clear();
		map = new SearchParameterMap();
		map.add(Patient.SP_BIRTHDATE, new DateParam("2020-01-01"));
		map.setLoadSynchronous(true);
		results = myPatientDao.search(map);
		ids = toUnqualifiedVersionlessIds(results);
		myCaptureQueriesListener.logSelectQueriesForCurrentThread();
		assertThat(ids, Matchers.empty());

	}

	private void createUniqueCompositeSp() {
		SearchParameter sp = new SearchParameter();
		sp.setId("SearchParameter/patient-birthdate");
		sp.setType(Enumerations.SearchParamType.DATE);
		sp.setCode("birthdate");
		sp.setExpression("Patient.birthDate");
		sp.setStatus(Enumerations.PublicationStatus.ACTIVE);
		sp.addBase("Patient");
		mySearchParameterDao.update(sp);

		sp = new SearchParameter();
		sp.setId("SearchParameter/patient-birthdate-unique");
		sp.setType(Enumerations.SearchParamType.COMPOSITE);
		sp.setStatus(Enumerations.PublicationStatus.ACTIVE);
		sp.addBase("Patient");
		sp.addComponent()
			.setExpression("Patient")
			.setDefinition("SearchParameter/patient-birthdate");
		sp.addExtension()
			.setUrl(SearchParamConstants.EXT_SP_UNIQUE)
			.setValue(new BooleanType(true));
		mySearchParameterDao.update(sp);

		mySearchParamRegistry.forceRefresh();
	}


	private void addCreateTenant(int thePartitionId, LocalDate theTenantDate) {
		registerInterceptorIfNeeded();
		myTenantInterceptor.addCreateTenant(new PartitionId(thePartitionId, theTenantDate));
	}

	private void addReadTenant(Integer thePartitionId) {
		registerInterceptorIfNeeded();
		PartitionId partitionId = null;
		if (thePartitionId != null) {
			partitionId = new PartitionId(thePartitionId, null);
		}
		myTenantInterceptor.addReadTenant(partitionId);
	}

	private void registerInterceptorIfNeeded() {
		if (!myInterceptorRegistry.getAllRegisteredInterceptors().contains(myTenantInterceptor)) {
			myInterceptorRegistry.registerInterceptor(myTenantInterceptor);
		}
	}

	public IIdType createPatient(Integer thePartitionId, Consumer<Patient>... theModifiers) {
		if (thePartitionId != null) {
			addCreateTenant(thePartitionId, null);
		}

		Patient p = new Patient();
		for (Consumer<Patient> next : theModifiers) {
			next.accept(p);
		}

		return myPatientDao.create(p, mySrd).getId().toUnqualifiedVersionless();
	}

	public void createRequestId() {
		when(mySrd.getRequestId()).thenReturn("REQUEST_ID");
	}

	private Consumer<Patient> withActiveTrue() {
		return t -> t.setActive(true);
	}

	private Consumer<Patient> withFamily(String theFamily) {
		return t -> t.addName().setFamily(theFamily);
	}

	private Consumer<Patient> withBirthdate(String theBirthdate) {
		return t -> t.getBirthDateElement().setValueAsString(theBirthdate);
	}

	private Consumer<Patient> withId(String theId) {
		return t -> {
			assertThat(theId, matchesPattern("[a-zA-Z0-9]+"));
			t.setId("Patient/" + theId);
		};
	}

	@Interceptor
	public static class MyInterceptor {


		private final List<PartitionId> myCreatePartitionIds = new ArrayList<>();
		private final List<PartitionId> myReadPartitionIds = new ArrayList<>();

		public void addCreateTenant(PartitionId thePartitionId) {
			Validate.notNull(thePartitionId);
			myCreatePartitionIds.add(thePartitionId);
		}

		public void addReadTenant(PartitionId thePartitionId) {
			myReadPartitionIds.add(thePartitionId);
		}

		@Hook(Pointcut.STORAGE_PARTITION_IDENTIFY_CREATE)
		public PartitionId tenantIdentifyCreate(IBaseResource theResource, ServletRequestDetails theRequestDetails) {
			assertNotNull(theResource);
			PartitionId retVal = myCreatePartitionIds.remove(0);
			ourLog.info("Returning partition for create: {}", retVal);
			return retVal;
		}

		@Hook(Pointcut.STORAGE_PARTITION_IDENTIFY_READ)
		public PartitionId tenantIdentifyRead(ServletRequestDetails theRequestDetails) {
			PartitionId retVal = myReadPartitionIds.remove(0);
			ourLog.info("Returning partition for read: {}", retVal);
			return retVal;
		}

		public void assertNoRemainingIds() {
			assertEquals(0, myCreatePartitionIds.size());
			assertEquals(0, myReadPartitionIds.size());
		}
	}

	@AfterClass
	public static void afterClassClearContext() {
		TestUtil.clearAllStaticFieldsForUnitTest();
	}

}
