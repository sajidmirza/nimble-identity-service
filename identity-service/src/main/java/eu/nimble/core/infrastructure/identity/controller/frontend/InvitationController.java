package eu.nimble.core.infrastructure.identity.controller.frontend;

import eu.nimble.core.infrastructure.identity.controller.IdentityUtils;
import eu.nimble.core.infrastructure.identity.entity.UaaUser;
import eu.nimble.core.infrastructure.identity.entity.UserInvitation;
import eu.nimble.core.infrastructure.identity.mail.EmailService;
import eu.nimble.core.infrastructure.identity.repository.*;
import eu.nimble.core.infrastructure.identity.uaa.OAuthClient;
import eu.nimble.core.infrastructure.identity.uaa.OpenIdConnectUserDetails;
import eu.nimble.service.model.ubl.commonaggregatecomponents.PartyType;
import eu.nimble.service.model.ubl.commonaggregatecomponents.PersonType;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.h2.jdbc.JdbcSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
public class InvitationController {

    private static final Logger logger = LoggerFactory.getLogger(UserIdentityController.class);

    @Autowired
    private PartyRepository partyRepository;

    @Autowired
    private UaaUserRepository uaaUserRepository;

    @Autowired
    private UserInvitationRepository userInvitationRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private IdentityUtils identityUtils;

    @ApiOperation(value = "", notes = "Send inviation to user.", response = ResponseEntity.class, tags = {})
    @RequestMapping(value = "/send_invitation", produces = {"application/json"}, method = RequestMethod.POST)
    ResponseEntity<?> sendInvitation(
            @ApiParam(value = "Invitation object.", required = true) @Valid @RequestBody UserInvitation invitation,
            @RequestHeader(value = "Authorization") String bearer,
            HttpServletRequest request) throws IOException, URISyntaxException {

        OpenIdConnectUserDetails userDetails = OpenIdConnectUserDetails.fromBearer(bearer);
        if (identityUtils.hasRole(bearer, OAuthClient.Role.LEGAL_REPRESENTATIVE) == false)
            return new ResponseEntity<>("Only legal representatives are allowd to invite users", HttpStatus.UNAUTHORIZED);

        // Todo: check if company ID matches with user

        // collect store invitation
        String email = invitation.getEmail();
        String companyId = invitation.getCompanyId();
        UaaUser sender = uaaUserRepository.findByExternalID(userDetails.getUserId());
        UserInvitation userInvitation = new UserInvitation(email, companyId, sender);

        try {
            // saving invitation with duplicate check
            userInvitationRepository.save(userInvitation);
        } catch (Exception ex) {
            logger.info("Impossible to register user {} twice for company {}", email, companyId);
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        // obtain sending company and user
        Optional<PartyType> parties = partyRepository.findByHjid(Long.parseLong(companyId)).stream().findFirst();
        if (parties.isPresent() == false) {
            logger.info("Requested party with Id {} not found", companyId);
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        PartyType company = parties.get();
        PersonType sendingPerson = sender.getUBLPerson();
        String senderName = sendingPerson.getFirstName() + " " + sendingPerson.getFamilyName();

        // send invitation
        emailService.sendInvite(email, senderName, company.getName());

        logger.info("Invitation sent: {} ({}, {}) -> {}", senderName, company.getName(), companyId, email);

        return new ResponseEntity<>(HttpStatus.OK);
    }


    @ApiOperation(value = "", notes = "Get pending invitations.", response = UserInvitation.class, responseContainer = "List", tags = {})
    @RequestMapping(value = "/invitations", produces = {"application/json"}, method = RequestMethod.GET)
    ResponseEntity<?> pendingInvitations(@RequestHeader(value = "Authorization") String bearer) throws IOException, URISyntaxException {
        UaaUser user = identityUtils.getUserfromBearer(bearer);

        Optional<PartyType> companyOpt = identityUtils.getCompanyOfUser(user);
        if (companyOpt.isPresent() == false) {
            logger.error("Requested party for user {} not found", user.getUsername());
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        PartyType company = companyOpt.get();

        List<UserInvitation> pendingInvitations = userInvitationRepository.findByCompanyId(company.getID());
        return new ResponseEntity<>(pendingInvitations, HttpStatus.OK);
    }
}
