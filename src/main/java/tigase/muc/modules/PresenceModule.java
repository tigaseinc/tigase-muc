/*
 * PresenceModule.java
 *
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2012 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 */



package tigase.muc.modules;

//~--- non-JDK imports --------------------------------------------------------

import tigase.component.ElementWriter;
import tigase.component.exceptions.RepositoryException;

import tigase.criteria.Criteria;
import tigase.criteria.ElementCriteria;

import tigase.muc.Affiliation;
import tigase.muc.DateUtil;
import tigase.muc.exceptions.MUCException;
import tigase.muc.history.HistoryProvider;
import tigase.muc.logger.MucLogger;
import tigase.muc.modules.PresenceModule.DelayDeliveryThread.DelDeliverySend;
import tigase.muc.MucConfig;
import tigase.muc.repository.IMucRepository;
import tigase.muc.Role;
import tigase.muc.Room;
import tigase.muc.RoomConfig;
import tigase.muc.RoomConfig.Anonymity;

import tigase.server.Message;
import tigase.server.Packet;
import tigase.server.Priority;

import tigase.util.TigaseStringprepException;

import tigase.xml.Element;
import tigase.xml.XMLNodeIfc;

import tigase.xmpp.Authorization;
import tigase.xmpp.BareJID;
import tigase.xmpp.JID;

//~--- JDK imports ------------------------------------------------------------

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Set;

/**
 * @author bmalkow
 *
 */
public class PresenceModule
				extends AbstractModule {
	private static final Criteria CRIT = ElementCriteria.name("presence");

	/** Field description */
	protected static final Logger log = Logger.getLogger(PresenceModule.class.getName());

	//~--- fields ---------------------------------------------------------------

	private final Set<Criteria> allowedElements = new HashSet<Criteria>();
	private boolean filterEnabled               = true;
	private boolean lockNewRoom                 = true;
	private final HistoryProvider historyProvider;
	private final MucLogger mucLogger;

	//~--- constructors ---------------------------------------------------------

	/**
	 * Constructs ...
	 *
	 *
	 * @param config
	 * @param writer
	 * @param mucRepository
	 * @param historyProvider
	 * @param sender
	 * @param mucLogger
	 */
	public PresenceModule(MucConfig config, ElementWriter writer,
												IMucRepository mucRepository, HistoryProvider historyProvider,
												DelDeliverySend sender, MucLogger mucLogger) {
		super(config, writer, mucRepository);
		this.historyProvider = historyProvider;
		this.mucLogger       = mucLogger;
		this.filterEnabled   = config.isPresenceFilterEnabled();
		allowedElements.add(ElementCriteria.name("show"));
		allowedElements.add(ElementCriteria.name("status"));
		allowedElements.add(ElementCriteria.name("priority"));
		allowedElements.add(ElementCriteria.xmlns("http://jabber.org/protocol/caps"));
		if (log.isLoggable(Level.CONFIG)) {
			log.config("Filtering presence children is " + (filterEnabled
							? "enabled"
							: "disabled"));
		}
	}

	//~--- get methods ----------------------------------------------------------

	private static Role getDefaultRole(final RoomConfig config,
																		 final Affiliation affiliation) {
		Role newRole;

		if (config.isRoomModerated() && (affiliation == Affiliation.none)) {
			newRole = Role.visitor;
		} else {
			switch (affiliation) {
			case admin :
				newRole = Role.moderator;

				break;

			case member :
				newRole = Role.participant;

				break;

			case none :
				newRole = Role.participant;

				break;

			case outcast :
				newRole = Role.none;

				break;

			case owner :
				newRole = Role.moderator;

				break;

			default :
				newRole = Role.none;

				break;
			}
		}

		return newRole;
	}

	//~--- methods --------------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param room
	 * @param destinationJID
	 * @param presence
	 * @param occupantJID
	 * @param occupantNickname
	 * @param occupantAffiliation
	 * @param occupantRole
	 *
	 * @return
	 *
	 * @throws TigaseStringprepException
	 */
	static PresenceWrapper preparePresenceW(Room room, JID destinationJID,
					final Element presence, BareJID occupantJID, String occupantNickname,
					Affiliation occupantAffiliation, Role occupantRole)
					throws TigaseStringprepException {
		Anonymity anonymity                      = room.getConfig().getRoomAnonymity();
		final Affiliation destinationAffiliation =
			room.getAffiliation(destinationJID.getBareJID());

		try {
			presence.setAttribute("from",
														JID.jidInstance(room.getRoomJID(),
															occupantNickname).toString());
		} catch (TigaseStringprepException e) {
			presence.setAttribute("from", room.getRoomJID() + "/" + occupantNickname);
		}
		presence.setAttribute("to", destinationJID.toString());

		Element x = new Element("x", new String[] { "xmlns" },
														new String[] { "http://jabber.org/protocol/muc#user" });
		Element item = new Element("item", new String[] { "affiliation", "role", "nick" },
															 new String[] { occupantAffiliation.name(),
						occupantRole.name(), occupantNickname });

		x.addChild(item);
		presence.addChild(x);

		Packet packet           = Packet.packetInstance(presence);
		PresenceWrapper wrapper = new PresenceWrapper(packet, x, item);

		if (occupantJID.equals(destinationJID.getBareJID())) {
			wrapper.packet.setPriority(Priority.HIGH);
			wrapper.addStatusCode(110);
			if (anonymity == Anonymity.nonanonymous) {
				wrapper.addStatusCode(100);
			}
			if (room.getConfig().isLoggingEnabled()) {
				wrapper.addStatusCode(170);
			}
		}
		if ((anonymity == Anonymity.nonanonymous) ||
				((anonymity == Anonymity.semianonymous) &&
				 destinationAffiliation.isViewOccupantsJid())) {
			item.setAttribute("jid", occupantJID.toString());
		}

		return wrapper;
	}

	/**
	 * Method description
	 *
	 *
	 * @param room
	 * @param destinationJID
	 * @param presence
	 * @param occupantJID
	 *
	 * @return
	 *
	 * @throws TigaseStringprepException
	 */
	static PresenceWrapper preparePresenceW(Room room, JID destinationJID,
					final Element presence, JID occupantJID)
					throws TigaseStringprepException {
		final String occupantNickname = room.getOccupantsNickname(occupantJID);

		return preparePresenceW(room, destinationJID, presence, occupantNickname);
	}

	// private final Set<Criteria> disallowedElements = new HashSet<Criteria>();

	/**
	 * Method description
	 *
	 *
	 * @param room
	 * @param destinationJID
	 * @param presence
	 * @param occupantNickname
	 *
	 * @return
	 *
	 * @throws TigaseStringprepException
	 */
	static PresenceWrapper preparePresenceW(Room room, JID destinationJID,
					final Element presence, String occupantNickname)
					throws TigaseStringprepException {
		final BareJID occupantJID             =
			room.getOccupantsJidByNickname(occupantNickname);
		final Affiliation occupantAffiliation = room.getAffiliation(occupantJID);
		final Role occupantRole               = room.getRole(occupantNickname);

		return preparePresenceW(room, destinationJID, presence, occupantJID,
														occupantNickname, occupantAffiliation, occupantRole);
	}

	private static Integer toInteger(String v, Integer defaultValue) {
		if (v == null) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(v);
		} catch (Exception e) {
			return defaultValue;
		}
	}

	/**
	 * @param room
	 * @param date
	 * @param senderJID
	 * @param nickName
	 */
	private void addJoinToHistory(Room room, Date date, JID senderJID, String nickName) {
		if (historyProvider != null) {
			historyProvider.addJoinEvent(room, date, senderJID, nickName);
		}
		if ((mucLogger != null) && room.getConfig().isLoggingEnabled()) {
			mucLogger.addJoinEvent(room, date, senderJID, nickName);
		}
	}

	/**
	 * @param room
	 * @param date
	 * @param senderJID
	 * @param nickName
	 */
	private void addLeaveToHistory(Room room, Date date, JID senderJID, String nickName) {
		if (historyProvider != null) {
			historyProvider.addLeaveEvent(room, date, senderJID, nickName);
		}
		if ((mucLogger != null) && room.getConfig().isLoggingEnabled()) {
			mucLogger.addLeaveEvent(room, date, senderJID, nickName);
		}
	}

	// private Element preparePresence(JID destinationJID, final Element
	// presence, Room room, BareJID roomJID,
	// String occupantNickname, Affiliation affiliation, Role role, JID
	// senderJID, boolean newRoomCreated,
	// String newNickName) {
	// }

	/**
	 * Method description
	 *
	 *
	 * @param element
	 *
	 * @return
	 */
	protected Element clonePresence(Element element) {
		Element presence = new Element(element);

		if (filterEnabled) {
			List<Element> cc = element.getChildren();

			if (cc != null) {
				@SuppressWarnings("rawtypes") List<XMLNodeIfc> children =
					new ArrayList<XMLNodeIfc>();

				for (Element c : cc) {
					for (Criteria crit : allowedElements) {
						if (crit.match(c)) {
							children.add(c);

							break;
						}
					}
				}
				presence.setChildren(children);
			}
		}

		Element toRemove = presence.getChild("x", "http://jabber.org/protocol/muc");

		if (toRemove != null) {
			presence.removeChild(toRemove);
		}

		return presence;
	}

	/**
	 * @param room
	 * @param senderJID
	 * @throws TigaseStringprepException
	 */
	public void doQuit(final Room room, final JID senderJID)
					throws TigaseStringprepException {
		final String leavingNickname         = room.getOccupantsNickname(senderJID);
		final Affiliation leavingAffiliation = room.getAffiliation(leavingNickname);
		final Role leavingRole               = room.getRole(leavingNickname);
		Element presenceElement              = new Element("presence");

		presenceElement.setAttribute("type", "unavailable");

		final PresenceWrapper selfPresence = preparePresenceW(room, senderJID,
																					 presenceElement, senderJID);
		boolean nicknameGone = room.removeOccupant(senderJID);

		room.updatePresenceByJid(senderJID, null);
		writer.write(selfPresence.packet);

		// TODO if highest priority is gone, then send current highest priority
		// to occupants
		if (nicknameGone) {
			for (String occupantNickname : room.getOccupantsNicknames()) {
				for (JID occupantJid : room.getOccupantsJidsByNickname(occupantNickname)) {
					presenceElement = new Element("presence");
					presenceElement.setAttribute("type", "unavailable");

					PresenceWrapper presence = preparePresenceW(room, occupantJid, presenceElement,
																			 senderJID.getBareJID(), leavingNickname,
																			 leavingAffiliation, leavingRole);

					writer.write(presence.packet);
				}
			}
			if (room.getConfig().isLoggingEnabled()) {
				addLeaveToHistory(room, new Date(), senderJID, leavingNickname);
			}
		}
		if (room.getOccupantsCount() == 0) {
			if ((historyProvider != null) &&!room.getConfig().isPersistentRoom()) {
				this.historyProvider.removeHistory(room);
			}
			this.repository.leaveRoom(room);
		}
	}

	//~--- get methods ----------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @return
	 */
	@Override
	public String[] getFeatures() {
		return null;
	}

	/**
	 * Method description
	 *
	 *
	 * @return
	 */
	@Override
	public Criteria getModuleCriteria() {
		return CRIT;
	}

	/**
	 * Method description
	 *
	 *
	 * @return
	 */
	public boolean isLockNewRoom() {
		return lockNewRoom;
	}

	//~--- methods --------------------------------------------------------------

	private PresenceWrapper preparePresence(JID destinationJID, final Element presence,
					Room room, JID occupantJID, boolean newRoomCreated, String newNickName)
					throws TigaseStringprepException {
		final PresenceWrapper wrapper = preparePresenceW(room, destinationJID, presence,
																			occupantJID);

		if (newRoomCreated) {
			wrapper.addStatusCode(201);
		}
		if (newNickName != null) {
			wrapper.addStatusCode(303);
			wrapper.item.setAttribute("nick", newNickName);
		}

		return wrapper;
	}

	/**
	 * Method description
	 *
	 *
	 * @param element
	 *
	 * @throws MUCException
	 * @throws TigaseStringprepException
	 */
	@Override
	public void process(Packet element) throws MUCException, TigaseStringprepException {
		final JID senderJID   =
			JID.jidInstance(element.getAttributeStaticStr(Packet.FROM_ATT));
		final BareJID roomJID =
			BareJID.bareJIDInstance(element.getAttributeStaticStr(Packet.TO_ATT));
		final String nickName =
			getNicknameFromJid(JID.jidInstance(element.getAttributeStaticStr(Packet.TO_ATT)));
		final String presenceType = element.getAttributeStaticStr(Packet.TYPE_ATT);

		// final String id = element.getAttribute("id");
		if ((presenceType != null) && "error".equals(presenceType)) {
			if (log.isLoggable(Level.FINER)) {
				log.finer("Ignoring presence with type='" + presenceType + "' from " + senderJID);
			}

			return;
		}
		if (nickName == null) {
			throw new MUCException(Authorization.JID_MALFORMED);
		}
		try {
			Room room = repository.getRoom(roomJID);

			if ((presenceType != null) && "unavailable".equals(presenceType)) {
				processExit(room, element.getElement(), senderJID);

				return;
			}

			final String knownNickname;
			final boolean roomCreated;

			if (room == null) {
				if (log.isLoggable(Level.INFO)) {
					log.info("Creating new room '" + roomJID + "' by user " + nickName + "' <" +
									 senderJID.toString() + ">");
				}
				room = repository.createNewRoom(roomJID, senderJID);
				room.addAffiliationByJid(senderJID.getBareJID(), Affiliation.owner);
				room.setRoomLocked(this.lockNewRoom);
				roomCreated   = true;
				knownNickname = null;
			} else {
				roomCreated   = false;
				knownNickname = room.getOccupantsNickname(senderJID);
			}

			final boolean probablyReEnter = element.getElement().getChild("x",
																				"http://jabber.org/protocol/muc") != null;

			if ((knownNickname != null) &&!knownNickname.equals(nickName)) {
				processChangeNickname(room, element.getElement(), senderJID, knownNickname,
															nickName);
			} else if (probablyReEnter || (knownNickname == null)) {
				processEntering(room, roomCreated, element.getElement(), senderJID, nickName);
			} else if (knownNickname.equals(nickName)) {
				processChangeAvailabilityStatus(room, element.getElement(), senderJID,
																				knownNickname);
			}
		} catch (MUCException e) {
			throw e;
		} catch (TigaseStringprepException e) {
			throw e;
		} catch (RepositoryException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Method description
	 *
	 *
	 * @param room
	 * @param presenceElement
	 * @param senderJID
	 * @param nickname
	 *
	 * @throws TigaseStringprepException
	 */
	protected void processChangeAvailabilityStatus(final Room room,
					final Element presenceElement, final JID senderJID, final String nickname)
					throws TigaseStringprepException {
		if (log.isLoggable(Level.FINEST)) {
			log.finest("Processing stanza " + presenceElement.toString());
		}
		room.updatePresenceByJid(null, clonePresence(presenceElement));

		Element pe = room.getLastPresenceCopyByJid(senderJID.getBareJID());

		sendPresenceToAllOccupants(pe, room, senderJID, false, null);
	}

	/**
	 * Method description
	 *
	 *
	 * @param room
	 * @param element
	 * @param senderJID
	 * @param senderNickname
	 * @param newNickName
	 *
	 * @throws MUCException
	 * @throws TigaseStringprepException
	 */
	protected void processChangeNickname(final Room room, final Element element,
					final JID senderJID, final String senderNickname, final String newNickName)
					throws TigaseStringprepException, MUCException {
		if (log.isLoggable(Level.FINEST)) {
			log.finest("Processing stanza " + element.toString());
		}

		throw new MUCException(Authorization.FEATURE_NOT_IMPLEMENTED, "Will me done soon");

		// TODO Example 23. Service Denies Room Join Because Roomnicks Are
		// Locked Down (???)
	}

	/**
	 * Method description
	 *
	 *
	 * @param room
	 * @param roomCreated
	 * @param element
	 * @param senderJID
	 * @param nickname
	 *
	 * @throws MUCException
	 * @throws TigaseStringprepException
	 */
	protected void processEntering(final Room room, final boolean roomCreated,
																 final Element element, final JID senderJID,
																 final String nickname)
					throws MUCException, TigaseStringprepException {
		if (log.isLoggable(Level.FINEST)) {
			log.finest("Processing stanza " + element.toString());
		}

		final Affiliation affiliation = room.getAffiliation(senderJID.getBareJID());
		final Element xElement        = element.getChild("x",
																			"http://jabber.org/protocol/muc");
		final Element password        = (xElement == null)
																		? null
																		: xElement.getChild("password");

		if (room.getConfig().isPasswordProtectedRoom()) {
			final String psw          = (password == null)
																	? null
																	: password.getCData();
			final String roomPassword = room.getConfig().getPassword();

			if ((psw == null) ||!psw.equals(roomPassword)) {

				// Service Denies Access Because No Password Provided
				if (log.isLoggable(Level.FINEST)) {
					log.finest("Password '" + psw + "' is not match to room password '" +
										 roomPassword + "' ");
				}

				throw new MUCException(Authorization.NOT_AUTHORIZED);
			}
		}
		if (room.isRoomLocked() && (affiliation != Affiliation.owner)) {

			// Service Denies Access Because Room Does Not (Yet) Exist
			throw new MUCException(Authorization.ITEM_NOT_FOUND, null,
														 "Room exists but is locked");
		}
		if (!affiliation.isEnterOpenRoom()) {

			// Service Denies Access Because User is Banned
			if (log.isLoggable(Level.INFO)) {
				log.info("User " + nickname + "' <" + senderJID.toString() + "> is on rooms '" +
								 room.getRoomJID() + "' blacklist");
			}

			throw new MUCException(Authorization.FORBIDDEN);
		} else if (room.getConfig().isRoomMembersOnly() &&
							 !affiliation.isEnterMembersOnlyRoom()) {

			// Service Denies Access Because User Is Not on Member List
			if (log.isLoggable(Level.INFO)) {
				log.info("User " + nickname + "' <" + senderJID.toString() +
								 "> is NOT on rooms '" + room.getRoomJID() + "' member list.");
			}

			throw new MUCException(Authorization.REGISTRATION_REQUIRED);
		}

		final BareJID currentOccupantJid = room.getOccupantsJidByNickname(nickname);

		if ((currentOccupantJid != null) &&
				!currentOccupantJid.equals(senderJID.getBareJID())) {

			// Service Denies Access Because of Nick Conflict
			throw new MUCException(Authorization.CONFLICT);
		}

		// TODO Service Informs User that Room Occupant Limit Has Been Reached
		// Service Sends Presence from Existing Occupants to New Occupant
		for (String occupantNickname : room.getOccupantsNicknames()) {
			final BareJID occupantJid = room.getOccupantsJidByNickname(occupantNickname);
			Element op                = room.getLastPresenceCopyByJid(occupantJid);
			PresenceWrapper l         = preparePresenceW(room, senderJID, op, occupantNickname);

			// presence.setAttribute("from", room.getRoomJID() + "/" +
			// occupantNickname);
			// presence.setAttribute("to", senderJID.toString());
			writer.write(l.packet);
		}

		final Role newRole = getDefaultRole(room.getConfig(), affiliation);

		if (log.isLoggable(Level.FINEST)) {
			log.finest("Occupant '" + nickname + "' <" + senderJID.toString() +
								 "> is entering room " + room.getRoomJID() + " as role=" +
								 newRole.name() + ", affiliation=" + affiliation.name());
		}
		room.addOccupantByJid(senderJID, nickname, newRole);

		Element pe = clonePresence(element);

		room.updatePresenceByJid(null, pe);
		if (currentOccupantJid == null) {

			// Service Sends New Occupant's Presence to All Occupants
			// Service Sends New Occupant's Presence to New Occupant
			sendPresenceToAllOccupants(room, senderJID, roomCreated, null);
		}

		Integer maxchars   = null;
		Integer maxstanzas = null;
		Integer seconds    = null;
		Date since         = null;
		Element hist       = (xElement == null)
												 ? null
												 : xElement.getChild("history");

		if (hist != null) {
			maxchars   = toInteger(hist.getAttributeStaticStr("maxchars"), null);
			maxstanzas = toInteger(hist.getAttributeStaticStr("maxstanzas"), null);
			seconds    = toInteger(hist.getAttributeStaticStr("seconds"), null);
			since      = DateUtil.parse(hist.getAttributeStaticStr("since"));
		}
		sendHistoryToUser(room, senderJID, maxchars, maxstanzas, seconds, since, writer);
		if ((room.getSubject() != null) && (room.getSubjectChangerNick() != null) &&
				(room.getSubjectChangeDate() != null)) {
			Element message = new Element(Message.ELEM_NAME, new String[] { Packet.TYPE_ATT,
							Packet.FROM_ATT, Packet.TO_ATT }, new String[] { "groupchat",
							room.getRoomJID() + "/" + room.getSubjectChangerNick(),
							senderJID.toString() });

			message.addChild(new Element("subject", room.getSubject()));

			String stamp  = DateUtil.formatDatetime(room.getSubjectChangeDate());
			Element delay = new Element("delay", new String[] { "xmlns", "stamp" },
																	new String[] { "urn:xmpp:delay",
							stamp });

			delay.setAttribute("jid", room.getRoomJID() + "/" + room.getSubjectChangerNick());

			Element x = new Element("x", new String[] { "xmlns", "stamp" },
															new String[] { "jabber:x:delay",
							DateUtil.formatOld(room.getSubjectChangeDate()) });

			message.addChild(delay);
			message.addChild(x);
			writer.writeElement(message);
		}
		if (room.isRoomLocked()) {
			sendMucMessage(room, room.getOccupantsNickname(senderJID),
										 "Room is locked. Please configure.");
		}
		if (roomCreated) {
			StringBuilder sb = new StringBuilder();

			sb.append("Welcome! You created new Multi User Chat Room.");
			if (room.isRoomLocked()) {
				sb.append(" Room is locked now. Configure it please!");
			} else {
				sb.append(" Room is unlocked and ready for occupants!");
			}
			sendMucMessage(room, room.getOccupantsNickname(senderJID), sb.toString());
		}
		if (room.getConfig().isLoggingEnabled()) {
			addJoinToHistory(room, new Date(), senderJID, nickname);
		}
	}

	/**
	 * Method description
	 *
	 *
	 * @param room
	 * @param presenceElement
	 * @param senderJID
	 *
	 * @throws MUCException
	 * @throws TigaseStringprepException
	 */
	protected void processExit(final Room room, final Element presenceElement,
														 final JID senderJID)
					throws MUCException, TigaseStringprepException {
		if (log.isLoggable(Level.FINEST)) {
			log.finest("Processing stanza " + presenceElement.toString());
		}
		if (room == null) {
			throw new MUCException(Authorization.ITEM_NOT_FOUND, "Unkown room");
		}

		final String leavingNickname = room.getOccupantsNickname(senderJID);

		if (leavingNickname == null) {
			throw new MUCException(Authorization.ITEM_NOT_FOUND, "Unkown occupant");
		}
		doQuit(room, senderJID);
	}

	/**
	 * @param room
	 * @param senderJID
	 * @param maxchars
	 * @param maxstanzas
	 * @param seconds
	 * @param since
	 */
	private void sendHistoryToUser(final Room room, final JID senderJID,
																 final Integer maxchars, final Integer maxstanzas,
																 final Integer seconds, final Date since,
																 final ElementWriter writer) {
		if (historyProvider != null) {
			historyProvider.getHistoryMessages(room, senderJID, maxchars, maxstanzas, seconds,
																				 since, writer);
		}
	}

	private void sendPresenceToAllOccupants(final Element $presence, Room room,
					JID senderJID, boolean newRoomCreated, String newNickName)
					throws TigaseStringprepException {
		for (String occupantNickname : room.getOccupantsNicknames()) {
			for (JID occupantJid : room.getOccupantsJidsByNickname(occupantNickname)) {
				PresenceWrapper presence = preparePresence(occupantJid, $presence.clone(), room,
																		 senderJID, newRoomCreated, newNickName);

				writer.write(presence.packet);
			}
		}
	}

	private void sendPresenceToAllOccupants(Room room, JID senderJID,
					boolean newRoomCreated, String newNickName)
					throws TigaseStringprepException {
		Element presence;

		if (newNickName != null) {
			presence = new Element("presence");
			presence.setAttribute("type", "unavailable");
		} else if (room.getOccupantsNickname(senderJID) == null) {
			presence = new Element("presence");
			presence.setAttribute("type", "unavailable");
		} else {
			presence = room.getLastPresenceCopyByJid(senderJID.getBareJID());
		}
		sendPresenceToAllOccupants(presence, room, senderJID, newRoomCreated, newNickName);
	}

	//~--- set methods ----------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param lockNewRoom
	 */
	public void setLockNewRoom(boolean lockNewRoom) {
		this.lockNewRoom = lockNewRoom;
	}

	//~--- inner classes --------------------------------------------------------

	/**
	 * Class description
	 *
	 *
	 * @version        Enter version here..., 13/02/20
	 * @author         Enter your name here...
	 */
	public static class DelayDeliveryThread
					extends Thread {
		private final LinkedList<Element[]> items = new LinkedList<Element[]>();
		private final DelDeliverySend sender;

		//~--- constructors -------------------------------------------------------

		/**
		 * Constructs ...
		 *
		 *
		 * @param component
		 */
		public DelayDeliveryThread(DelDeliverySend component) {
			this.sender = component;
		}

		//~--- methods ------------------------------------------------------------

		/**
		 * @param elements
		 */
		public void put(Collection<Element> elements) {
			if ((elements != null) && (elements.size() > 0)) {
				items.push(elements.toArray(new Element[] {}));
			}
		}

		/**
		 * Method description
		 *
		 *
		 * @param element
		 */
		public void put(Element element) {
			items.add(new Element[] { element });
		}

		/**
		 * Method description
		 *
		 */
		@Override
		public void run() {
			try {
				do {
					sleep(553);
					if (items.size() > 0) {
						Element[] toSend = items.poll();

						if (toSend != null) {
							for (Element element : toSend) {
								try {
									sender.sendDelayedPacket(Packet.packetInstance(element));
								} catch (TigaseStringprepException ex) {
									if (log.isLoggable(Level.INFO)) {
										log.info("Packet addressing problem, stringprep failed: " + element);
									}
								}
							}
						}
					}
				} while (true);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		//~--- inner interfaces ---------------------------------------------------

		/**
		 * Interface description
		 *
		 *
		 * @version        Enter version here..., 13/02/20
		 * @author         Enter your name here...
		 */
		public static interface DelDeliverySend {
			/**
			 * Method description
			 *
			 *
			 * @param packet
			 */
			void sendDelayedPacket(Packet packet);
		}
	}


	/**
	 * Class description
	 *
	 *
	 * @version        Enter version here..., 13/02/20
	 * @author         Enter your name here...
	 */
	public static class PresenceWrapper {
		final Element item;
		final Packet packet;
		final Element x;

		//~--- constructors -------------------------------------------------------

		PresenceWrapper(Packet packet, Element x, Element item) {
			this.packet = packet;
			this.x      = x;
			this.item   = item;
		}

		//~--- methods ------------------------------------------------------------

		/**
		 * Method description
		 *
		 *
		 * @param code
		 */
		void addStatusCode(int code) {
			x.addChild(new Element("status", new String[] { "code" },
														 new String[] { "" + code }));
		}
	}
}


//~ Formatted in Tigase Code Convention on 13/02/20
