package cz.metacentrum.perun.core.impl;

import cz.metacentrum.perun.core.api.AssignedGroup;
import cz.metacentrum.perun.core.api.Attribute;
import cz.metacentrum.perun.core.api.BeansUtils;
import cz.metacentrum.perun.core.api.EnrichedGroup;
import cz.metacentrum.perun.core.api.ExtSource;
import cz.metacentrum.perun.core.api.Facility;
import cz.metacentrum.perun.core.api.Group;
import cz.metacentrum.perun.core.api.GroupResourceStatus;
import cz.metacentrum.perun.core.api.GroupsManager;
import cz.metacentrum.perun.core.api.Member;
import cz.metacentrum.perun.core.api.MemberGroupStatus;
import cz.metacentrum.perun.core.api.MembershipType;
import cz.metacentrum.perun.core.api.Pair;
import cz.metacentrum.perun.core.api.PerunSession;
import cz.metacentrum.perun.core.api.Resource;
import cz.metacentrum.perun.core.api.SecurityTeam;
import cz.metacentrum.perun.core.api.Status;
import cz.metacentrum.perun.core.api.User;
import cz.metacentrum.perun.core.api.Vo;
import cz.metacentrum.perun.core.api.exceptions.AlreadyMemberException;
import cz.metacentrum.perun.core.api.exceptions.ConsistencyErrorException;
import cz.metacentrum.perun.core.api.exceptions.GroupAlreadyRemovedException;
import cz.metacentrum.perun.core.api.exceptions.GroupExistsException;
import cz.metacentrum.perun.core.api.exceptions.GroupNotExistsException;
import cz.metacentrum.perun.core.api.exceptions.GroupRelationDoesNotExist;
import cz.metacentrum.perun.core.api.exceptions.InternalErrorException;
import cz.metacentrum.perun.core.api.exceptions.NotGroupMemberException;
import cz.metacentrum.perun.core.api.exceptions.ParentGroupNotExistsException;
import cz.metacentrum.perun.core.bl.DatabaseManagerBl;
import cz.metacentrum.perun.core.blImpl.AuthzResolverBlImpl;
import cz.metacentrum.perun.core.implApi.GroupsManagerImplApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcPerunTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Implementation of GroupsManager
 *
 * @author Michal Prochazka michalp@ics.muni.cz
 * @author Slavek Licehammer glory@ics.muni.cz
 */
public class GroupsManagerImpl implements GroupsManagerImplApi {

	private final static Logger log = LoggerFactory.getLogger(GroupsManagerImpl.class);

	public final static int MEMBERSGROUP = 1;
	public final static int ADMINSGROUP = 2;
	public final static int SUBGROUP = 3;

	protected final static String groupMappingSelectQuery = "groups.id as groups_id, groups.uu_id as groups_uu_id, groups.parent_group_id as groups_parent_group_id, groups.name as groups_name, groups.dsc as groups_dsc, "
			+ "groups.vo_id as groups_vo_id, groups.created_at as groups_created_at, groups.created_by as groups_created_by, groups.modified_by as groups_modified_by, groups.modified_at as groups_modified_at, "
			+ "groups.modified_by_uid as groups_modified_by_uid, groups.created_by_uid as groups_created_by_uid ";

	protected final static String assignedGroupMappingSelectQuery = groupMappingSelectQuery + ", groups_resources_state.status as groups_resources_state_status, " +
		"groups_resources_automatic.auto_assign_subgroups as auto_assign_subgroups, groups_resources_state.failure_cause as groups_resources_state_failure_cause, groups_resources_automatic.source_group_id as groups_resources_automatic_source_group_id";


	// http://static.springsource.org/spring/docs/3.0.x/spring-framework-reference/html/jdbc.html
	private final JdbcPerunTemplate jdbc;
	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

	// Group mapper
	protected static final RowMapper<Group> GROUP_MAPPER = (resultSet, i) -> {
		Group g = new Group();
		g.setId(resultSet.getInt("groups_id"));
		g.setUuid(resultSet.getObject("groups_uu_id", UUID.class));
		//ParentGroup with ID=0 is not supported
		if(resultSet.getInt("groups_parent_group_id") != 0) g.setParentGroupId(resultSet.getInt("groups_parent_group_id"));
		else g.setParentGroupId(null);
		g.setName(resultSet.getString("groups_name"));
		g.setShortName(g.getName().substring(g.getName().lastIndexOf(":") + 1));
		g.setDescription(resultSet.getString("groups_dsc"));
		g.setVoId(resultSet.getInt("groups_vo_id"));
		g.setCreatedAt(resultSet.getString("groups_created_at"));
		g.setCreatedBy(resultSet.getString("groups_created_by"));
		g.setModifiedAt(resultSet.getString("groups_modified_at"));
		g.setModifiedBy(resultSet.getString("groups_modified_by"));
		if(resultSet.getInt("groups_modified_by_uid") == 0) g.setModifiedByUid(null);
		else g.setModifiedByUid(resultSet.getInt("groups_modified_by_uid"));
		if(resultSet.getInt("groups_created_by_uid") == 0) g.setCreatedByUid(null);
		else g.setCreatedByUid(resultSet.getInt("groups_created_by_uid"));
		return g;
	};

	protected static final RowMapper<AssignedGroup> ASSIGNED_GROUP_MAPPER = (resultSet, i) -> {
		Group group = GROUP_MAPPER.mapRow(resultSet, i);
		EnrichedGroup enrichedGroup = new EnrichedGroup(group, null);
		Integer sourceGroupId = resultSet.getInt("groups_resources_automatic_source_group_id");
		sourceGroupId = resultSet.wasNull() ? null : sourceGroupId;
		String failureCause = resultSet.getString("groups_resources_state_failure_cause");
		boolean autoAssignSubgroups = resultSet.getBoolean("auto_assign_subgroups");
		return new AssignedGroup(enrichedGroup, GroupResourceStatus.valueOf(resultSet.getString("groups_resources_state_status")), sourceGroupId, failureCause, autoAssignSubgroups);
	};

	private static final RowMapper<Pair<Group, Resource>> GROUP_RESOURCE_MAPPER = (resultSet, i) -> {
		Pair<Group, Resource> pair = new Pair<>();
		pair.put(GROUP_MAPPER.mapRow(resultSet, i), ResourcesManagerImpl.RESOURCE_MAPPER.mapRow(resultSet, i));
		return pair;
	};

	private static final ResultSetExtractor<Map<Integer, List<Integer>>> MEMBERID_MEMBERGROUPSTATUS_EXTRACTOR = resultSet -> {
		Map<Integer, List<Integer>> map = new HashMap<>();
		while (resultSet.next()) {
			Integer memberId = resultSet.getInt("member_id");
			List<Integer> list = map.get(memberId);
			if (list == null) {
				list = new ArrayList<>();
			}
			list.add(resultSet.getInt("source_group_status"));
			map.put(memberId, list);
		}
		return map;
	};

	/**
	 * Create new instance of this class.
	 *
	 */
	public GroupsManagerImpl(DataSource perunPool) {
		this.jdbc = new JdbcPerunTemplate(perunPool);
		this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(perunPool);
		this.jdbc.setQueryTimeout(BeansUtils.getCoreConfig().getQueryTimeout());
		this.namedParameterJdbcTemplate.getJdbcTemplate().setQueryTimeout(BeansUtils.getCoreConfig().getQueryTimeout());
	}

	@Override
	public Group createGroup(PerunSession sess, Vo vo, Group group) throws GroupExistsException {
		Utils.notNull(group, "group");
		Utils.notNull(group.getName(), "group.getName()");

		// Check if the group already exists
		if(group.getParentGroupId() == null) {
			// check if the TOP level group exists
			if (1 == jdbc.queryForInt("select count('x') from groups where lower(name)=lower(?) and vo_id=? and parent_group_id IS NULL", group.getName(), vo.getId())) {
				throw new GroupExistsException("Group [" + group.getName() + "] already exists under VO [" + vo.getShortName() + "] and has parent Group with id is [NULL]");
			}
		} else {
			// check if subgroup exists under parent group
			if (1 == jdbc.queryForInt("select count('x') from groups where lower(name)=lower(?) and vo_id=? and parent_group_id=?", group.getName(), vo.getId(), group.getParentGroupId())) {
				throw new GroupExistsException("Group [" + group.getName() + "] already exists under VO [" + vo.getShortName() + "] and has parent Group with id [" + group.getParentGroupId() + "]");
			}
		}

		Utils.validateGroupName(group.getShortName());

		int newId;
		try {
			// Store the group into the DB
			newId = Utils.getNewId(jdbc, "groups_id_seq");

			jdbc.update("insert into groups (id, parent_group_id, name, dsc, vo_id, created_by,created_at,modified_by,modified_at,created_by_uid,modified_by_uid) " +
					"values (?,?,?,?,?,?," + Compatibility.getSysdate() + ",?," + Compatibility.getSysdate() + ",?,?)", newId, group.getParentGroupId(),
					group.getName(), group.getDescription(), vo.getId(), sess.getPerunPrincipal().getActor(), sess.getPerunPrincipal().getActor(), sess.getPerunPrincipal().getUserId(), sess.getPerunPrincipal().getUserId());
		} catch (RuntimeException err) {
			throw new InternalErrorException(err);
		}
		Group newGroup;
		try {
			newGroup = getGroupById(sess, newId);
		} catch (GroupNotExistsException e) {
			throw new InternalErrorException("Failed to read newly created group with id: " + newId, e);
		}
		group.setId(newId);
		group.setUuid(newGroup.getUuid());
		group.setVoId(newGroup.getVoId());

		return newGroup;
	}



	public String getName(int id) {
		List name;
		try {
			name = jdbc.query("group.name as (with temp (name, id, parent_group_id) as ((select name, id, parent_group_id from GROUPS where parent_group_id is null) union all (select cast((temp.name + ':' + groups.name) as varchar(128)), " +
					"groups.id, groups.parent_group_id from groups inner join temp on temp.id = groups.parent_group_id )) select name from temp where group.id = ?"
					, (RowMapper) (resultSet, i) -> resultSet.getString(1),id);
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
		String result=(String)name.get(0);
		return result;
	}

	/*
	 * Create a subgroup
	 *
	 * @see cz.metacentrum.perun.core.implApi.GroupsManagerImplApi#createGroup(cz.metacentrum.perun.core.api.PerunSession, cz.metacentrum.perun.core.api.Vo, cz.metacentrum.perun.core.api.Group, cz.metacentrum.perun.core.api.Group)
	 */
	@Override
	public Group createGroup(PerunSession sess, Vo vo, Group parentGroup, Group group) throws GroupExistsException {
		// Create new subGroup

		group.setParentGroupId(parentGroup.getId());

		group.setName(parentGroup.getName()+":"+group.getShortName());

		group = createGroup(sess, vo, group);

		return group;
	}

	@Override
	public void deleteGroup(PerunSession sess, Vo vo, Group group) throws GroupAlreadyRemovedException {
		Utils.notNull(group.getName(), "group.getName()");

		try {
			// Delete group's members
			jdbc.update("delete from groups_members where group_id=?", group.getId());

			// Delete authz entries for this group
			AuthzResolverBlImpl.removeAllAuthzForGroup(sess, group);

			int rowAffected = jdbc.update("delete from groups where id=?", group.getId());
			if(rowAffected == 0) throw new GroupAlreadyRemovedException("Group: " + group + " , Vo: " + vo);
		} catch (RuntimeException err) {
			throw new InternalErrorException(err);
		}
	}

	@Override
	public Group updateGroup(PerunSession sess, Group group) throws GroupExistsException {
		Utils.notNull(group.getName(), "group.getName()");

		// Get the group stored in the DB
		Group dbGroup;
		try {
			dbGroup = this.getGroupById(sess, group.getId());
		} catch (GroupNotExistsException e) {
			throw new InternalErrorException("Group existence was checked at the higher level",e);
		}

		// we allow only update on shortName part of name
		if (!dbGroup.getShortName().equals(group.getShortName())) {
			Utils.validateGroupName(group.getShortName());
			dbGroup.setShortName(group.getShortName());
			try {
				jdbc.update("update groups set name=?,modified_by=?, modified_by_uid=?, modified_at=" + Compatibility.getSysdate() + " where id=?", dbGroup.getName(),
						sess.getPerunPrincipal().getActor(), sess.getPerunPrincipal().getUserId(), dbGroup.getId());
			} catch (DataIntegrityViolationException e) {
				throw new GroupExistsException("The name must be unique and it's already occupied - " + dbGroup, e);
			} catch (RuntimeException e) {
				throw new InternalErrorException(e);
			}
		}

		if (group.getDescription() != null && !group.getDescription().equals(dbGroup.getDescription())) {
			try {
				jdbc.update("update groups set dsc=?, modified_by=?, modified_by_uid=?, modified_at=" + Compatibility.getSysdate() + " where id=?", group.getDescription(),
						sess.getPerunPrincipal().getActor(), sess.getPerunPrincipal().getUserId(), group.getId());
			} catch (RuntimeException e) {
				throw new InternalErrorException(e);
			}
			dbGroup.setDescription(group.getDescription());
		}

		return dbGroup;
	}

	@Override
	public Group updateGroupName(PerunSession sess, Group group) {
		Utils.notNull(group.getName(), "group.getName()");

		// Get the group stored in the DB
		Group dbGroup;
		try {
			dbGroup = this.getGroupById(sess, group.getId());
		} catch (GroupNotExistsException e) {
			throw new InternalErrorException("Group existence was checked at the higher level",e);
		}

		if (!dbGroup.getName().equals(group.getName())) {
			dbGroup.setName(group.getName());
			try {
				jdbc.update("update groups set name=?,modified_by=?, modified_by_uid=?, modified_at=" + Compatibility.getSysdate() + " where id=?", dbGroup.getName(),
						sess.getPerunPrincipal().getActor(), sess.getPerunPrincipal().getUserId(), dbGroup.getId());
			} catch (RuntimeException e) {
				throw new InternalErrorException(e);
			}
		}
		return dbGroup;
	}

	@Override
	public Group updateParentGroupId(PerunSession sess, Group group) {
		Utils.notNull(group, "group");

		// Get the group stored in the DB
		Group dbGroup;
		try {
			dbGroup = this.getGroupById(sess, group.getId());
		} catch (GroupNotExistsException e) {
			throw new InternalErrorException("Group existence was checked at the higher level",e);
		}

		//check if group parent id was changed to another id or to null
		if ((group.getParentGroupId() != null && !group.getParentGroupId().equals(dbGroup.getParentGroupId())) ||
				(group.getParentGroupId() == null && dbGroup.getParentGroupId() != null)) {
			dbGroup.setParentGroupId(group.getParentGroupId());
			try {
				jdbc.update("update groups set parent_group_id=?,modified_by=?, modified_by_uid=?, modified_at=" + Compatibility.getSysdate() + " where id=?", dbGroup.getParentGroupId(),
						sess.getPerunPrincipal().getActor(), sess.getPerunPrincipal().getUserId(), dbGroup.getId());
			} catch (RuntimeException e) {
				throw new InternalErrorException(e);
			}
		}

		return dbGroup;
	}

	@Override
	public Group getGroupById(PerunSession sess, int id) throws GroupNotExistsException {
		try {
			return jdbc.queryForObject("select " + groupMappingSelectQuery + " from groups where groups.id=? ", GROUP_MAPPER, id);
		} catch (EmptyResultDataAccessException err) {
			throw new GroupNotExistsException("Group id=" + id);
		} catch (RuntimeException err) {
			throw new InternalErrorException(err);
		}
	}

	@Override
	public List<User> getGroupUsers(PerunSession sess, Group group) {
		try {
			return jdbc.query("select " + UsersManagerImpl.userMappingSelectQuery + " from groups_members join members on members.id=member_id join " +
					"users on members.user_id=users.id where group_id=? order by " + Compatibility.orderByBinary("users.last_name") + ", " +
					Compatibility.orderByBinary("users.first_name"), UsersManagerImpl.USER_MAPPER, group.getId());
		} catch(RuntimeException ex) {
			throw new InternalErrorException(ex);
		}
	}

	@Override
	public boolean isUserMemberOfGroup(PerunSession sess, User user, Group group) {
		try {
			return 1 <= jdbc.queryForInt("select count(1) from groups_members join members on members.id = member_id where members.user_id=? and groups_members.group_id=?", user.getId(), group.getId());
		} catch(RuntimeException ex) {
			throw new InternalErrorException(ex);
		}
	}

	@Override
	public List<Member> getGroupMembers(PerunSession sess, Group group) {
		try {
			return jdbc.query("select " + MembersManagerImpl.groupsMembersMappingSelectQuery + " from groups_members join members on members.id=groups_members.member_id " +
					"where groups_members.group_id=?", MembersManagerImpl.MEMBER_MAPPER_WITH_GROUP, group.getId());
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Member> getGroupMembersById(PerunSession sess, Group group, int id) {
		try {
			return jdbc.query("select " + MembersManagerImpl.groupsMembersMappingSelectQuery +
					" from groups_members" +
					" join members on members.id=groups_members.member_id" +
					" and groups_members.group_id=? " +
					" and members.id=?", MembersManagerImpl.MEMBER_MAPPER_WITH_GROUP, group.getId(), id);
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Member> getGroupMembersByMembership(PerunSession sess, Group group, MembershipType membershipType) {
		try {
			return jdbc.query("select " + MembersManagerImpl.groupsMembersMappingSelectQuery + " from groups_members join members on members.id=groups_members.member_id " +
					"where groups_members.group_id=? and groups_members.membership_type=?", MembersManagerImpl.MEMBER_MAPPER_WITH_GROUP, group.getId(), membershipType.getCode());
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Member> getGroupMembers(PerunSession sess, Group group, List<Status> statuses, boolean excludeStatus) {
		try {
			MapSqlParameterSource parameters = new MapSqlParameterSource();
			List<Integer> statusesCodes = new ArrayList<>();
			for (Status status: statuses) {
				statusesCodes.add(status.getCode());
			}
			parameters.addValue("statuses", statusesCodes);
			parameters.addValue("group_id", group.getId());

			if (excludeStatus) {
				// Exclude members with one of the status
				return this.namedParameterJdbcTemplate.query("select " + MembersManagerImpl.groupsMembersMappingSelectQuery +
						" from groups_members join members on members.id=groups_members.member_id " +
						"where groups_members.group_id=:group_id and members.status not in (:statuses)", parameters, MembersManagerImpl.MEMBER_MAPPER_WITH_GROUP);
			} else {
				// Include members with one of the status
				return this.namedParameterJdbcTemplate.query("select " + MembersManagerImpl.groupsMembersMappingSelectQuery +
						" from groups_members join members on members.id=groups_members.member_id " +
						"where groups_members.group_id=:group_id and members.status in (:statuses)", parameters, MembersManagerImpl.MEMBER_MAPPER_WITH_GROUP);
			}
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Group> getGroups(PerunSession sess, Vo vo) {
		try {
			return jdbc.query("select  " + groupMappingSelectQuery + " from groups where vo_id=? order by " +
							Compatibility.orderByBinary("groups.name" + Compatibility.castToVarchar()),
					GROUP_MAPPER, vo.getId());

		} catch(RuntimeException ex) {
			throw new InternalErrorException(ex);
		}
	}

	@Override
	public List<Group> getAssignedGroupsToResource(PerunSession perunSession, Resource resource) {
		try {
			return jdbc.query("select " + groupMappingSelectQuery + " from groups join " +
					" groups_resources_state on groups.id=groups_resources_state.group_id " +
					" where groups_resources_state.resource_id=? and groups_resources_state.status=?::group_resource_status",
					GROUP_MAPPER, resource.getId(), GroupResourceStatus.ACTIVE.toString());
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Group> getAssignedGroupsToResource(PerunSession perunSession, Resource resource, Member member) {
		try {
			return jdbc.query("select " + groupMappingSelectQuery + " from groups join " +
							" groups_resources_state on groups.id=groups_resources_state.group_id and groups_resources_state.resource_id=? " +
							" and groups_resources_state.status=?::group_resource_status" +
							" join groups_members on groups_members.group_id=groups.id and groups_members.member_id=?",
					GROUP_MAPPER, resource.getId(), GroupResourceStatus.ACTIVE.toString(), member.getId());
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Group> getAssignedGroupsToFacility(PerunSession perunSession, Facility facility) {
		try {
			return jdbc.query("select distinct " + groupMappingSelectQuery + " from groups join " +
							" groups_resources_state on groups.id=groups_resources_state.group_id and groups_resources_state.status=?::group_resource_status " +
							" join resources on groups_resources_state.resource_id=resources.id " +
							"where resources.facility_id=?",
					GROUP_MAPPER, GroupResourceStatus.ACTIVE.toString(), facility.getId());
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Group> getSubGroups(PerunSession sess, Group parentGroup) {
		try {
			return jdbc.query("select " + groupMappingSelectQuery + " from groups where groups.parent_group_id=? " +
							"order by " + Compatibility.orderByBinary("groups.name" + Compatibility.castToVarchar()),
					GROUP_MAPPER, parentGroup.getId());
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch(RuntimeException ex) {
			throw new InternalErrorException(ex);
		}
	}

	@Override
	public int getSubGroupsCount(PerunSession sess, Group parentGroup) {
		try {
			return jdbc.queryForInt("select count(1) from groups where parent_group_id=?", parentGroup.getId());
		} catch(RuntimeException ex) {
			throw new InternalErrorException(ex);
		}
	}

	@Override
	public List<Group> getAllGroups(PerunSession sess, Vo vo) {
		try {
			return jdbc.query("select " + groupMappingSelectQuery + " from groups where vo_id=?", GROUP_MAPPER, vo.getId());
		} catch(RuntimeException ex) {
			throw new InternalErrorException(ex);
		}

	}

	@Override
	public Group getParentGroup(PerunSession sess, Group group) throws ParentGroupNotExistsException {
		try  {
			return jdbc.queryForObject("select " + groupMappingSelectQuery + " from groups where groups.id=?",
					GROUP_MAPPER, group.getParentGroupId());
		} catch (EmptyResultDataAccessException e) {
			throw new ParentGroupNotExistsException(e);
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public Group getGroupByName(PerunSession sess, Vo vo, String name) throws GroupNotExistsException {
		try {
			return jdbc.queryForObject("select " + groupMappingSelectQuery + " from groups where groups.name=? and groups.vo_id=?",
					GROUP_MAPPER, name, vo.getId());
		} catch (EmptyResultDataAccessException err) {
			throw new GroupNotExistsException("Group name=" + name + ", vo id=" + vo.getId());
		} catch (RuntimeException err) {
			throw new InternalErrorException(err);
		}
	}

	@Override
	public List<Group> getGroupsByIds(PerunSession perunSession, List<Integer> ids) {
		try {
			return jdbc.execute("select " + groupMappingSelectQuery + " from groups where id " + Compatibility.getStructureForInClause(),
				(PreparedStatementCallback<List<Group>>) preparedStatement -> {
					Array sqlArray = DatabaseManagerBl.prepareSQLArrayOfNumbersFromIntegers(ids, preparedStatement);
					preparedStatement.setArray(1, sqlArray);
					ResultSet rs = preparedStatement.executeQuery();
					List<Group> groups = new ArrayList<>();
					while (rs.next()) {
						groups.add(GROUP_MAPPER.mapRow(rs, rs.getRow()));
					}
					return groups;
				});
		} catch (RuntimeException ex) {
			throw new InternalErrorException(ex);
		}
	}

	@Override
	public Member addMember(PerunSession sess, Group group, Member member, MembershipType type, int sourceGroupId) throws AlreadyMemberException {
		member.setMembershipType(type);
		member.setSourceGroupId(sourceGroupId);
		try {
			jdbc.update("insert into groups_members (group_id, member_id, created_by, created_at, modified_by, modified_at, created_by_uid, modified_by_uid, membership_type, source_group_id) " +
					"values (?,?,?," + Compatibility.getSysdate() + ",?," + Compatibility.getSysdate() + ",?,?,?,?)", group.getId(),
					member.getId(), sess.getPerunPrincipal().getActor(), sess.getPerunPrincipal().getActor(), sess.getPerunPrincipal().getUserId(), sess.getPerunPrincipal().getUserId(), type.getCode(), sourceGroupId);
		} catch(DuplicateKeyException ex) {
			throw new AlreadyMemberException(member);
		} catch(RuntimeException ex) {
			throw new InternalErrorException(ex);
		}
		return member;

	}

	@Override
	public List<Group> getAllMemberGroups(PerunSession sess, Member member) {
		try {
			return jdbc.query("select distinct " + groupMappingSelectQuery + " from groups_members join groups on groups_members.group_id = groups.id " +
					" where groups_members.member_id=?",
					GROUP_MAPPER, member.getId());
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Group> getAllGroupsWhereMemberIsActive(PerunSession sess, Member member) {
		try {
			return jdbc.query("select distinct " + groupMappingSelectQuery + " from groups_members join groups on groups_members.group_id = groups.id " +
					" where groups_members.member_id=? and groups_members.source_group_status=?",
				GROUP_MAPPER, member.getId(), MemberGroupStatus.VALID.getCode());
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Group> getGroupsByAttribute(PerunSession sess, Attribute attribute) {
		try {
			return jdbc.query("select " + groupMappingSelectQuery + " from groups " +
					"join group_attr_values on groups.id=group_attr_values.group_id where group_attr_values.attr_id=? and " +
					"group_attr_values.attr_value=?",
					GROUP_MAPPER, attribute.getId(), BeansUtils.attributeValueToString(attribute));
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Pair<Group,Resource>> getGroupResourcePairsByAttribute(PerunSession sess, Attribute attribute) {
		try {
			return jdbc.query("select " + groupMappingSelectQuery + ", " + ResourcesManagerImpl.resourceMappingSelectQuery +
					" from group_resource_attr_values " +
					"join groups on groups.id=group_resource_attr_values.group_id " +
					"join resources on resources.id=group_resource_attr_values.resource_id " +
					"where group_resource_attr_values.attr_id=? and group_resource_attr_values.attr_value=?",
					GROUP_RESOURCE_MAPPER, attribute.getId(), BeansUtils.attributeValueToString(attribute));
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public boolean isGroupMember(PerunSession sess, Group group, Member member) {
		try {
			return 1 <= jdbc.queryForInt("select count(1) from groups_members where group_id=? and member_id=?", group.getId(), member.getId());
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public boolean isDirectGroupMember(PerunSession sess, Group group, Member member) {
		try {
			int count = jdbc.queryForInt("select count(1) from groups_members where group_id=? and member_id=? and membership_type = ?", group.getId(), member.getId(), MembershipType.DIRECT.getCode());
			if (1 < count) throw new ConsistencyErrorException("There is more than one direct member in group" + group);
			return 1 == count;
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public void removeMember(PerunSession sess, Group group, Member member) throws NotGroupMemberException {
		if (member.getSourceGroupId() == null) {
			throw new InternalErrorException("sourceGroupId not set for member object");
		}
		int ret;
		try {
			ret = jdbc.update("delete from groups_members where group_id=? and source_group_id=? and member_id=?", group.getId(), member.getSourceGroupId(), member.getId());
		} catch(RuntimeException ex) {
			throw new InternalErrorException(ex);
		}
		if(ret == 0) {
			throw new NotGroupMemberException(group, member);
		} else if (ret < 1) {
			throw new ConsistencyErrorException(member + " and " + group + " have " + ret + " rows in groups_members table");
		}

	}

	@Override
	public List<User> getAdmins(PerunSession sess, Group group) {
		try {
			// direct admins
			Set<User> setOfAdmins = new HashSet<>(jdbc.query("select " + UsersManagerImpl.userMappingSelectQuery + " from authz join users on authz.user_id=users.id " +
				"where authz.group_id=? and authz.role_id=(select id from roles where name='groupadmin')", UsersManagerImpl.USER_MAPPER, group.getId()));

			// admins through a group
			List<Group> listOfGroupAdmins = getGroupAdmins(sess, group);
			for(Group authorizedGroup : listOfGroupAdmins) {
				setOfAdmins.addAll(jdbc.query("select " + UsersManagerImpl.userMappingSelectQuery + " from users join members on users.id=members.user_id " +
							"join groups_members on groups_members.member_id=members.id where groups_members.group_id=?", UsersManagerImpl.USER_MAPPER, authorizedGroup.getId()));
			}

			return new ArrayList(setOfAdmins);

		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<User> getDirectAdmins(PerunSession sess, Group group) {
		try {
			return jdbc.query("select " + UsersManagerImpl.userMappingSelectQuery + " from authz join users on authz.user_id=users.id " +
					"where authz.group_id=? and authz.role_id=(select id from roles where name='groupadmin')", UsersManagerImpl.USER_MAPPER, group.getId());
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Group> getGroupAdmins(PerunSession sess, Group group) {
		try {
			return jdbc.query("select " + groupMappingSelectQuery + " from authz join groups on authz.authorized_group_id=groups.id " +
					"where authz.group_id=? and authz.role_id=(select id from roles where name='groupadmin')",
					GROUP_MAPPER, group.getId());
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public int getGroupsCount(PerunSession sess, Vo vo) {
		try {
			return jdbc.queryForInt("select count(1) from groups where vo_id=?", vo.getId());
		} catch(RuntimeException ex) {
			throw new InternalErrorException(ex);
		}
	}

	@Override
	public int getVoId(PerunSession sess, Group group) {
		try {
			return jdbc.queryForInt("select vo_id from groups where id=?", group.getId());
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public void checkGroupExists(PerunSession sess, Group group) throws GroupNotExistsException {
		if (!groupExists(sess, group)) throw new GroupNotExistsException("Group " + group);
	}

	@Override
	public boolean groupExists(PerunSession sess, Group group) {
		try {
			int numberOfExistences = jdbc.queryForInt("select count(1) from groups where id=?", group.getId());
			if (numberOfExistences == 1) {
				return true;
			} else if (numberOfExistences > 1) {
				throw new ConsistencyErrorException("Group " + group + " exists more than once.");
			}
			return false;
		} catch(EmptyResultDataAccessException ex) {
			return false;
		} catch (RuntimeException ex) {
			throw new InternalErrorException(ex);
		}
	}

	/**
	 * Gets all groups which have enabled synchronization.
	 *
	 * @param sess
	 * @return list of groups to synchronize
	 * @throws InternalErrorException
	 */
	@Override
	public List<Group> getGroupsToSynchronize(PerunSession sess) {
		try {
			// Get all groups which have defined
			return jdbc.query("select " + groupMappingSelectQuery + " from groups, attr_names, group_attr_values " +
					"where attr_names.attr_name=? and attr_names.id=group_attr_values.attr_id and group_attr_values.attr_value='true' and " +
					"group_attr_values.group_id=groups.id", GROUP_MAPPER, GroupsManager.GROUPSYNCHROENABLED_ATTRNAME);
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Group> getGroupsStructuresToSynchronize(PerunSession sess) {
		try {
			// Get all groups which have defined synchronization
			return jdbc.query("select " + groupMappingSelectQuery + " from groups, attr_names, group_attr_values " +
					"where attr_names.attr_name=? and attr_names.id=group_attr_values.attr_id and group_attr_values.attr_value='true' and " +
					"group_attr_values.group_id=groups.id", GROUP_MAPPER, GroupsManager.GROUPS_STRUCTURE_SYNCHRO_ENABLED_ATTRNAME);
		} catch (EmptyResultDataAccessException e) {
			return new ArrayList<>();
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Integer> getGroupApplicationIds(PerunSession sess, Group group) {
		// get app ids for all applications
		try {
			return jdbc.query("select id from application where group_id=?", (resultSet, i) -> resultSet.getInt("id"),group.getId());
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Pair<String, String>> getApplicationReservedLogins(Integer appId) {
		try {
			return jdbc.query("select namespace,login from application_reserved_logins where app_id=?",
				(resultSet, arg1) -> new Pair<>(resultSet.getString("namespace"), resultSet.getString("login")), appId);
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public void deleteGroupReservedLogins(PerunSession sess, Group group) {
		// remove all reserved logins first
		try {
			for (Integer appId : getGroupApplicationIds(sess, group)) {
				jdbc.update("delete from application_reserved_logins where app_id=?", appId);
			}
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public int getGroupsCount(PerunSession sess) {
		try {
			return jdbc.queryForInt("select count(*) from groups");
		} catch (RuntimeException ex) {
			throw new InternalErrorException(ex);
		}
	}

	@Override
	public List<Group> getGroupsWithAssignedExtSourceInVo(PerunSession sess, ExtSource source, Vo vo) {
		try {
			return jdbc.query("select " + groupMappingSelectQuery +
					" from group_ext_sources g_exts inner join groups on g_exts.group_id=groups.id " +
					" where g_exts.ext_source_id=? and groups.vo_id=?", GROUP_MAPPER, source.getId(), vo.getId());

		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public void removeGroupUnion(PerunSession sess, Group resultGroup, Group operandGroup) throws GroupRelationDoesNotExist {
		try {
			if (0 == jdbc.update("DELETE FROM groups_groups WHERE result_gid = ? AND operand_gid = ?",
					resultGroup.getId(), operandGroup.getId())) {
				throw new GroupRelationDoesNotExist("Union between " + resultGroup + " and " + operandGroup + " does not exist.");
			}
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public void removeResultGroupRelations(PerunSession sess, Group resultGroup) {
		try {
			jdbc.update("DELETE FROM groups_groups WHERE result_gid = ?", resultGroup.getId());
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public void saveGroupRelation(PerunSession sess, Group resultGroup, Group operandGroup, boolean parentFlag) {
		try {
			jdbc.update("INSERT INTO groups_groups(result_gid, operand_gid, created_at, created_by, " +
						"modified_at, modified_by, parent_flag) VALUES(?,?," + Compatibility.getSysdate() + ",?," + Compatibility.getSysdate() + ",?,?)",
					resultGroup.getId(), operandGroup.getId(), sess.getPerunPrincipal().getActor(), sess.getPerunPrincipal().getActor(), parentFlag);
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public boolean isRelationRemovable(PerunSession sess, Group resultGroup, Group operandGroup) {
		try {
			return 1 > jdbc.queryForInt("SELECT parent_flag"+Compatibility.castToInteger()+" FROM groups_groups WHERE result_gid=? AND operand_gid=?",
					resultGroup.getId(), operandGroup.getId());
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public boolean isRelationBetweenGroups(Group group1, Group group2) {
		try {
			return 1 <= jdbc.queryForInt("SELECT count(1) FROM groups_groups WHERE (result_gid = ? AND operand_gid = ?) OR (result_gid = ? AND operand_gid = ?)",
				group1.getId(), group2.getId(), group2.getId(), group1.getId());
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public boolean isOneWayRelationBetweenGroups(Group resultGroup, Group operandGroup) {
		try {
			return 1 <= jdbc.queryForInt("SELECT count(1) FROM groups_groups WHERE result_gid = ? AND operand_gid = ?",
					resultGroup.getId(), operandGroup.getId());
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Group> getResultGroups(PerunSession sess, int groupId) {
		try {
			return jdbc.query("SELECT " + groupMappingSelectQuery + " FROM groups_groups JOIN groups " +
					"ON groups.id = groups_groups.result_gid WHERE operand_gid=?", GROUP_MAPPER, groupId);
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Group> getOperandGroups(PerunSession sess, int groupId) {
		try {
			return jdbc.query("SELECT " + groupMappingSelectQuery + " FROM groups_groups JOIN groups " +
					"ON groups.id = groups_groups.operand_gid WHERE result_gid=?", GROUP_MAPPER, groupId);
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Integer> getResultGroupsIds(PerunSession sess, int groupId) {
		try {
			return jdbc.queryForList("SELECT result_gid FROM groups_groups WHERE operand_gid=?", Integer.class, groupId);
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public void setIndirectGroupStatus(PerunSession sess, Member member, Group group, MemberGroupStatus status) {
		try {
			jdbc.update("UPDATE groups_members SET source_group_status=?, modified_by=?, modified_at=" + Compatibility.getSysdate() +
					" WHERE source_group_id=? AND group_id <> source_group_id AND member_id=?", status.getCode(), sess.getPerunPrincipal().getActor(), group.getId(), member.getId());
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public void setDirectGroupStatus(PerunSession sess, Member member, Group group, MemberGroupStatus status) {
		try {
			jdbc.update("UPDATE groups_members SET source_group_status=?, modified_by=?, modified_at=" + Compatibility.getSysdate() +
					" WHERE source_group_id=? AND group_id = source_group_id AND member_id=?", status.getCode(), sess.getPerunPrincipal().getActor(), group.getId(), member.getId());
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public MemberGroupStatus getDirectMemberGroupStatus(PerunSession session, Member member, Group group) {
		try {
			return MemberGroupStatus.getMemberGroupStatus(jdbc.queryForInt("SELECT source_group_status FROM groups_members " +
					"WHERE source_group_id=? AND group_id=? and member_id=?", group.getId(), group.getId(), member.getId()));
		} catch (EmptyResultDataAccessException e) {
			return null;
		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public MemberGroupStatus getTotalMemberGroupStatus(PerunSession session, Member member, Group group) {
		try {
			List<Integer> list = jdbc.queryForList("SELECT source_group_status FROM groups_members " +
					"WHERE group_id=? and member_id=?",Integer.class, group.getId(), member.getId());
			if (list.contains(0)) {
				// found valid status
				return MemberGroupStatus.VALID;
			// check if contains any expired status
			} else if (list.contains(1)) {
				return MemberGroupStatus.EXPIRED;
			}
			return null;
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
		catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public Map<Integer, MemberGroupStatus> getTotalGroupStatusForMembers(PerunSession session, Group group, List<Member> members) {
		List<Integer> memberIds = new ArrayList<>();
		members.forEach(member -> memberIds.add(member.getId()));

		MapSqlParameterSource parameters = new MapSqlParameterSource();
		parameters.addValue("groupId", group.getId());
		parameters.addValue("memberIds", memberIds);

		try {
			Map<Integer, List<Integer>> map = namedParameterJdbcTemplate.query("select member_id, source_group_status FROM groups_members" +
				" join members on groups_members.member_id=members.id where group_id=(:groupId) and member_id in (:memberIds)", parameters, MEMBERID_MEMBERGROUPSTATUS_EXTRACTOR);

			Map<Integer, MemberGroupStatus> resultMap = new HashMap<>();

			if (map == null) {
				return null;
			}

			for (Integer memberId : map.keySet()) {
				if (map.get(memberId).contains(0)) {
					resultMap.put(memberId, MemberGroupStatus.VALID);
				} else {
					resultMap.put(memberId, MemberGroupStatus.EXPIRED);
				}
			}

			return resultMap;

		} catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Facility> getFacilitiesWhereGroupIsAdmin(PerunSession session, Group group) {
		try {
			return jdbc.query("select " + FacilitiesManagerImpl.facilityMappingSelectQuery + " from authz join facilities on authz.facility_id=facilities.id " +
					"where authorized_group_id=? and authz.role_id=(select id from roles where name='facilityadmin')", FacilitiesManagerImpl.FACILITY_MAPPER, group.getId());
		}  catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Group> getGroupsWhereGroupIsAdmin(PerunSession session, Group group) {
		try {
			return jdbc.query("select " + GroupsManagerImpl.groupMappingSelectQuery + " from authz join groups on authz.group_id=groups.id " +
					"where authorized_group_id=? and authz.role_id=(select id from roles where name='groupadmin')", GroupsManagerImpl.GROUP_MAPPER, group.getId());
		}  catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Resource> getResourcesWhereGroupIsAdmin(PerunSession session, Group group) {
		try {
			return jdbc.query("select " + ResourcesManagerImpl.resourceMappingSelectQuery + " from authz join resources " +
					"on authz.resource_id=resources.id where authorized_group_id=? and authz.role_id=(select id from roles where name='resourceadmin')",
					ResourcesManagerImpl.RESOURCE_MAPPER, group.getId());
		}  catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Resource> getResourcesWhereGroupIsResourceSelfService(PerunSession session, Group group) {
		try {
			return jdbc.query("select " + ResourcesManagerImpl.resourceMappingSelectQuery + " from authz join resources " +
					"on authz.resource_id=resources.id where authorized_group_id=? and authz.role_id=(select id from roles where name='resourceselfservice')",
				ResourcesManagerImpl.RESOURCE_MAPPER, group.getId());
		}  catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<SecurityTeam> getSecurityTeamsWhereGroupIsAdmin(PerunSession session, Group group) {
		try {
			return jdbc.query("select " + SecurityTeamsManagerImpl.securityTeamMappingSelectQuery + " from authz join security_teams " +
					"on authz.security_team_id=security_teams.id where authorized_group_id=? and authz.role_id=(select id from roles where name='securityadmin')",
					SecurityTeamsManagerImpl.SECURITY_TEAM_MAPPER, group.getId());
		}  catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Vo> getVosWhereGroupIsAdmin(PerunSession session, Group group) {
		try {
			return jdbc.query("select " + VosManagerImpl.voMappingSelectQuery + " from authz join vos on authz.vo_id=vos.id " +
					"where authorized_group_id=? and authz.role_id=(select id from roles where name='voadmin')", VosManagerImpl.VO_MAPPER, group.getId());
		}  catch (RuntimeException e) {
			throw new InternalErrorException(e);
		}
	}

	@Override
	public List<Group> getGroupsForAutoRegistration(PerunSession sess, Vo vo) {
		try {
			return jdbc.query("select " + groupMappingSelectQuery + " from groups where vo_id=? and id IN (select group_id from groups_to_register)", GROUP_MAPPER, vo.getId());
		} catch (RuntimeException ex) {
			throw new InternalErrorException(ex);
		}
	}

	@Override
	public void deleteGroupFromAutoRegistration(PerunSession sess, Group group) {
		try {
			jdbc.update("delete from groups_to_register where group_id=?", group.getId());
		} catch (RuntimeException err) {
			throw new InternalErrorException(err);
		}
	}

	@Override
	public void addGroupToAutoRegistration(PerunSession sess, Group group) {
		try {
			jdbc.update("insert into groups_to_register (group_id) values (?)", group.getId());
		} catch (RuntimeException err) {
			throw new InternalErrorException(err);
		}
	}

	@Override
	public boolean isGroupForAutoRegistration(PerunSession sess, Group group) {
		try {
			return 0 < jdbc.queryForInt("SELECT count(1) FROM groups_to_register WHERE group_id = ?", group.getId());
		} catch (RuntimeException err) {
			throw new InternalErrorException(err);
		}
	}
}
