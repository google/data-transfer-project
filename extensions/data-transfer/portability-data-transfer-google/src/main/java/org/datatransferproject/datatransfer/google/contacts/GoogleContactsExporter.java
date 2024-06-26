/*
 * Copyright 2018 The Data Transfer Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.datatransferproject.datatransfer.google.contacts;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.services.people.v1.PeopleService;
import com.google.api.services.people.v1.PeopleService.People.Connections;
import com.google.api.services.people.v1.model.EmailAddress;
import com.google.api.services.people.v1.model.FieldMetadata;
import com.google.api.services.people.v1.model.GetPeopleResponse;
import com.google.api.services.people.v1.model.ListConnectionsResponse;
import com.google.api.services.people.v1.model.Name;
import com.google.api.services.people.v1.model.Person;
import com.google.api.services.people.v1.model.PersonResponse;
import com.google.api.services.people.v1.model.PhoneNumber;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import ezvcard.VCard;
import ezvcard.io.json.JCardWriter;
import ezvcard.property.Email;
import ezvcard.property.StructuredName;
import ezvcard.property.Telephone;
import org.datatransferproject.datatransfer.google.common.GoogleCredentialFactory;
import org.datatransferproject.datatransfer.google.common.GoogleStaticObjects;
import org.datatransferproject.spi.transfer.provider.ExportResult;
import org.datatransferproject.spi.transfer.provider.ExportResult.ResultType;
import org.datatransferproject.spi.transfer.provider.Exporter;
import org.datatransferproject.spi.transfer.types.ContinuationData;
import org.datatransferproject.types.common.ExportInformation;
import org.datatransferproject.types.common.PaginationData;
import org.datatransferproject.types.common.StringPaginationToken;
import org.datatransferproject.types.common.models.contacts.ContactsModelWrapper;
import org.datatransferproject.types.transfer.auth.TokensAndUrlAuthData;

import java.io.IOException;
import java.io.StringWriter;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.datatransferproject.datatransfer.google.common.GoogleStaticObjects.PERSON_FIELDS;
import static org.datatransferproject.datatransfer.google.common.GoogleStaticObjects.SELF_RESOURCE;
import static org.datatransferproject.datatransfer.google.common.GoogleStaticObjects.SOURCE_PARAM_NAME_TYPE;
import static org.datatransferproject.datatransfer.google.common.GoogleStaticObjects.VCARD_PRIMARY_PREF;

public class GoogleContactsExporter
    implements Exporter<TokensAndUrlAuthData, ContactsModelWrapper> {

  private final GoogleCredentialFactory credentialFactory;
  private volatile PeopleService peopleService;

  public GoogleContactsExporter(GoogleCredentialFactory credentialFactory) {
    this.credentialFactory = credentialFactory;
    this.peopleService = null; // lazily initialized for the given request
  }

  @VisibleForTesting
  GoogleContactsExporter(PeopleService peopleService) {
    this.credentialFactory = null; // unused in tests
    this.peopleService = peopleService;
  }

  @VisibleForTesting
  static VCard convert(Person person) {
    VCard vCard = new VCard();

    /* Reluctant to set the VCard.Kind value, since a) there aren't that many type options for
    Google contacts,
    b) those type options are often wrong, and c) those type options aren't even reliably in the
    same place.
    Source: https://developers.google.com/people/api/rest/v1/people#personmetadata
    */

    Preconditions.checkArgument(
        atLeastOneNamePresent(person.getNames()), "At least one name must be present");
    convertToVCardNamesAndPopulate(vCard, person.getNames());

    if (person.getAddresses() != null) {
      // VCard API does not support adding multiple addresses at once
      person.getAddresses().forEach(a -> vCard.addAddress(convertToVCardAddress(a)));
    }

    if (person.getPhoneNumbers() != null) {
      // VCard API does not support adding multiple telephone numbers at once
      person.getPhoneNumbers().forEach(n -> vCard.addTelephoneNumber(convertToVCardTelephone(n)));
    }

    if (person.getEmailAddresses() != null) {
      // VCard API does not support adding multiple emails at once
      person.getEmailAddresses().forEach(e -> vCard.addEmail(convertToVCardEmail(e)));
    }

    return vCard;
  }

  private static void convertToVCardNamesAndPopulate(VCard vCard, List<Name> personNames) {
    // If there are multiple primary names, we consider the first one as primary
    // and add remaining primary names in alternateStructuredNames
    StructuredName primaryStructuredName = null;
    LinkedList<StructuredName> alternateStructuredNames = new LinkedList<>();
    for (Name personName : personNames) {
      StructuredName structuredName = convertToVCardNameSingle(personName);
      if (isPrimaryName(personName,primaryStructuredName)) {
        // This is the (a?) primary name for the Person, so it should be the primary name in the
        // VCard.
        primaryStructuredName = structuredName;
      } else {
        alternateStructuredNames.add(structuredName);
      }
    }

    if (primaryStructuredName == null) {
      primaryStructuredName = alternateStructuredNames.pop();
    }

    vCard.addProperty(primaryStructuredName);
    vCard.addPropertyAlt(StructuredName.class, alternateStructuredNames);
  }
  private static boolean isPrimaryName(Name personName,StructuredName primaryStructuredName){
    Boolean isNamePrimary = personName.getMetadata().getPrimary();
    return primaryStructuredName == null && isNamePrimary != null && isNamePrimary;
  }


  private static StructuredName convertToVCardNameSingle(Name personName) {
    StructuredName structuredName = new StructuredName();
    structuredName.setFamily(personName.getFamilyName());
    structuredName.setGiven(personName.getGivenName());
    structuredName.setParameter(
        SOURCE_PARAM_NAME_TYPE, personName.getMetadata().getSource().getType());

    // TODO(olsona): address formatting, structure, phonetics, suffixes, prefixes
    return structuredName;
  }

  private static ezvcard.property.Address convertToVCardAddress(
      com.google.api.services.people.v1.model.Address personAddress) {
    ezvcard.property.Address vCardAddress = new ezvcard.property.Address();

    vCardAddress.setCountry(personAddress.getCountry());
    vCardAddress.setRegion(personAddress.getRegion());
    vCardAddress.setLocality(personAddress.getCity());
    vCardAddress.setPostalCode(personAddress.getPostalCode());
    vCardAddress.setStreetAddress(personAddress.getStreetAddress());
    vCardAddress.setPoBox(personAddress.getPoBox());
    vCardAddress.setExtendedAddress(personAddress.getExtendedAddress());
    vCardAddress.setPref(getPref(personAddress.getMetadata()));

    return vCardAddress;
  }

  private static Telephone convertToVCardTelephone(PhoneNumber personNumber) {
    Telephone telephone = new Telephone(personNumber.getValue());
    telephone.setPref(getPref(personNumber.getMetadata()));
    return telephone;
  }

  private static Email convertToVCardEmail(EmailAddress personEmail) {
    // TODO(olsona): address Email.displayName
    // TODO(olsona): address Email.formattedType
    Email email = new Email(personEmail.getValue());
    email.setPref(getPref(personEmail.getMetadata()));

    return email;
  }

  private static int getPref(FieldMetadata metadata) {
    return metadata.getPrimary() ? VCARD_PRIMARY_PREF : VCARD_PRIMARY_PREF + 1;
  }

  private static boolean atLeastOneNamePresent(List<Name> personNames) {
    return personNames.size() >= 1 && !personNames.get(0).isEmpty();
  }

  @VisibleForTesting
  static String makeVCardString(List<VCard> vCardList) throws IOException {
    StringWriter stringWriter = new StringWriter();
    JCardWriter jCardWriter = new JCardWriter(stringWriter);
    for (VCard vCardProperties : vCardList) { // needs to be loop so error can be thrown
      jCardWriter.write(vCardProperties);
    }
    jCardWriter.flush();
    return stringWriter.toString();
  }

  @Override
  public ExportResult<ContactsModelWrapper> export(
      UUID jobId, TokensAndUrlAuthData authData, Optional<ExportInformation> exportInformation) {
    if (exportInformation.isPresent()) {
      StringPaginationToken stringPaginationToken = (StringPaginationToken)
          exportInformation.get().getPaginationData();
      return exportContacts(authData, Optional.ofNullable(stringPaginationToken));
    } else {
      return exportContacts(authData, Optional.empty());
    }
  }

  private ExportResult<ContactsModelWrapper> exportContacts(
      TokensAndUrlAuthData authData, Optional<PaginationData> pageData) {
    try {
      // Set up connection
      Connections.List connectionsListRequest =
          getOrCreatePeopleService(authData).people().connections().list(SELF_RESOURCE);

      // Get next page, if we have a page token
      if (pageData.isPresent()) {
        StringPaginationToken paginationToken = (StringPaginationToken) pageData.get();
        connectionsListRequest.setPageToken(paginationToken.getToken());
      }

      // Get list of connections (nb: not a list containing full info of each Person)
      ListConnectionsResponse response =
          connectionsListRequest.setPersonFields(PERSON_FIELDS).execute();
      List<Person> peopleList = response.getConnections();

      // Get list of resource names, then get list of Persons
      List<String> resourceNames =
          peopleList.stream().map(Person::getResourceName).collect(Collectors.toList());
      GetPeopleResponse batchResponse =
          getOrCreatePeopleService(authData)
              .people()
              .getBatchGet()
              .setResourceNames(resourceNames)
              .setPersonFields(PERSON_FIELDS)
              .execute();
      List<PersonResponse> personResponseList = batchResponse.getResponses();

      // Convert Persons to VCards
      List<VCard> vCards =
          personResponseList.stream().map(a -> convert(a.getPerson())).collect(Collectors.toList());

      // Determine if there's a next page
      StringPaginationToken nextPageData = null;
      if (response.getNextPageToken() != null) {
        nextPageData = new StringPaginationToken(response.getNextPageToken());
      }
      ContinuationData continuationData = new ContinuationData(nextPageData);

      ContactsModelWrapper wrapper = new ContactsModelWrapper(makeVCardString(vCards));

      // Get result type
      ResultType resultType = ResultType.CONTINUE;
      if (nextPageData == null) {
        resultType = ResultType.END;
      }

      return new ExportResult<ContactsModelWrapper>(resultType, wrapper, continuationData);
    } catch (IOException e) {
      return new ExportResult<ContactsModelWrapper>(e);
    }
  }

  private PeopleService getOrCreatePeopleService(TokensAndUrlAuthData authData) {
    return peopleService == null ? makePeopleService(authData) : peopleService;
  }

  private synchronized PeopleService makePeopleService(TokensAndUrlAuthData authData) {
    Credential credential = credentialFactory.createCredential(authData);
    return new PeopleService.Builder(
        credentialFactory.getHttpTransport(), credentialFactory.getJsonFactory(), credential)
        .setApplicationName(GoogleStaticObjects.APP_NAME)
        .build();
  }
}
