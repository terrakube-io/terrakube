package io.terrakube.api.rs;

import com.yahoo.elide.annotation.*;
import io.terrakube.api.rs.agent.Agent;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.globalvar.Globalvar;
import io.terrakube.api.rs.hooks.organization.OrganizationManageHook;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.module.Module;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.provider.Provider;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.tag.Tag;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

@ReadPermission(expression = "user belongs organization")
@CreatePermission(expression = "user is a superuser")
@UpdatePermission(expression = "user is a superuser")
@DeletePermission(expression = "user is a superuser")
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = OrganizationManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT, hook = OrganizationManageHook.class)
@Include
@Getter
@Setter
@Entity(name = "organization")
@SQLRestriction(value = "disabled = false")
public class Organization {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    private String name;

    @Column(name = "disabled")
    private boolean disabled;

    @Column(name = "description")
    private String description;

    @UpdatePermission(expression = "user belongs organization")
    @OneToMany(mappedBy = "organization")
    private List<Collection> collection;

    @UpdatePermission(expression = "user belongs organization")
    @OneToMany(mappedBy = "organization")
    private List<Workspace> workspace;

    @UpdatePermission(expression = "user belongs organization")
    @OneToMany(mappedBy = "organization")
    private List<Project> project;

    @UpdatePermission(expression = "user belongs organization")
    @OneToMany(mappedBy = "organization")
    private List<Module> module;

    @UpdatePermission(expression = "user belongs organization")
    @OneToMany(mappedBy = "organization")
    private List<Provider> provider;

    @UpdatePermission(expression = "user belongs organization")
    @OneToMany(mappedBy = "organization")
    private List<Job> job;

    @OneToMany(mappedBy = "organization")
    private List<Team> team;

    @UpdatePermission(expression = "user belongs organization")
    @OneToMany(mappedBy = "organization")
    private List<Vcs> vcs;

    @UpdatePermission(expression = "user belongs organization")
    @OneToMany(mappedBy = "organization")
    private List<Ssh> ssh;

    @UpdatePermission(expression = "user belongs organization")
    @OneToMany(mappedBy = "organization")
    private List<Agent> agent;

    @UpdatePermission(expression = "user belongs organization")
    @OneToMany(mappedBy = "organization")
    private List<Template> template;

    @OneToMany(mappedBy = "organization")
    private List<Globalvar> globalvar;

    @OneToMany(mappedBy = "organization")
    private List<Tag> tag;

    @Column(name = "execution_mode")
    private String executionMode;

    @Column(name = "icon")
    private String icon;
}
