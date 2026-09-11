import { render, screen, fireEvent } from "@testing-library/react";
import React from "react";
import { MemoryRouter } from "react-router-dom";
import {
  PolicySetCard,
  PolicySetFilter,
  PolicySetTable,
} from "../components";

describe("PolicySet Components", () => {
  const mockItem = {
    id: "ps-test-1",
    attributes: {
      name: "sample-policy",
      description: "Sample test description",
      enforcementLevel: "HARD_MANDATORY",
      shadowEnforcementLevel: "SOFT_MANDATORY",
      global: false,
      overrideTeam: "devops-leads",
      repository: "https://github.com/org/policies",
      branch: "main",
      folder: "/rules",
    },
  };

  describe("PolicySetCard", () => {
    it("renders card metadata, tags, and handles edit and delete actions", () => {
      const onEdit = jest.fn();
      const onDelete = jest.fn();

      render(
        <MemoryRouter>
          <PolicySetCard
            item={mockItem}
            attachmentsCount={3}
            managePermission={true}
            onEdit={onEdit}
            onDelete={onDelete}
            orgid="org-1"
          />
        </MemoryRouter>
      );

      expect(screen.getByText("sample-policy")).toBeInTheDocument();
      expect(screen.getByText("Sample test description")).toBeInTheDocument();
      expect(screen.getByText("Hard Mandatory")).toBeInTheDocument();
      expect(screen.getByText("Shadow: SOFT_MANDATORY")).toBeInTheDocument();
      expect(screen.getByText("3 Attachments")).toBeInTheDocument();
      expect(screen.getByText("Override Team: devops-leads")).toBeInTheDocument();
      expect(screen.getByText("https://github.com/org/policies")).toBeInTheDocument();
      expect(screen.getByText("main")).toBeInTheDocument();
      expect(screen.getByText("/rules")).toBeInTheDocument();

      fireEvent.click(screen.getByTestId("edit-policy-set-btn-ps-test-1"));
      expect(onEdit).toHaveBeenCalledWith("ps-test-1");

      fireEvent.click(screen.getByTestId("delete-policy-set-btn-ps-test-1"));
      expect(onDelete).toHaveBeenCalledWith(mockItem);
    });

    it("renders Global tag when policy set is global", () => {
      const globalItem = {
        ...mockItem,
        attributes: { ...mockItem.attributes, global: true },
      };

      render(
        <MemoryRouter>
          <PolicySetCard
            item={globalItem}
            attachmentsCount={0}
            managePermission={true}
            onEdit={jest.fn()}
            onDelete={jest.fn()}
            orgid="org-1"
          />
        </MemoryRouter>
      );

      expect(screen.getByText("Global")).toBeInTheDocument();
      expect(screen.queryByText(/Attachment/)).not.toBeInTheDocument();
    });

    it("renders Notification tag when notificationConfig is provided", () => {
      render(
        <MemoryRouter>
          <PolicySetCard
            item={mockItem}
            attachmentsCount={1}
            managePermission={true}
            onEdit={jest.fn()}
            onDelete={jest.fn()}
            orgid="org-1"
            notificationConfig={{ name: "SecOps Slack", channelType: "SLACK" }}
          />
        </MemoryRouter>
      );

      expect(screen.getByTestId("policy-set-notification-ps-test-1")).toBeInTheDocument();
      expect(screen.getByText("Notification: SecOps Slack")).toBeInTheDocument();
    });
  });

  describe("PolicySetTable", () => {
    it("renders compact table columns and triggers actions", () => {
      const onEdit = jest.fn();
      const onDelete = jest.fn();
      const onPageChange = jest.fn();

      const itemWithNotif = {
        ...mockItem,
        relationships: {
          notificationConfiguration: { data: { id: "notif-1" } },
        },
      };

      render(
        <MemoryRouter>
          <PolicySetTable
            policySets={[itemWithNotif]}
            attachmentCounts={{ "ps-test-1": 3 }}
            managePermission={true}
            onEdit={onEdit}
            onDelete={onDelete}
            orgid="org-1"
            currentPage={1}
            pageSize={10}
            onPageChange={onPageChange}
            notificationConfigs={{ "notif-1": { id: "notif-1", name: "SecOps Webhook", channelType: "WEBHOOK" } }}
          />
        </MemoryRouter>
      );

      expect(screen.getByTestId("policy-sets-compact-table")).toBeInTheDocument();
      expect(screen.getByText("sample-policy")).toBeInTheDocument();
      expect(screen.getByText("Hard Mandatory")).toBeInTheDocument();
      expect(screen.getByText("3 Attachments")).toBeInTheDocument();
      expect(screen.getByText("Override: devops-leads")).toBeInTheDocument();
      expect(screen.getByTestId("policy-set-table-notif-ps-test-1")).toBeInTheDocument();
      expect(screen.getByText("SecOps Webhook")).toBeInTheDocument();

      fireEvent.click(screen.getByTestId("table-edit-policy-set-btn-ps-test-1"));
      expect(onEdit).toHaveBeenCalledWith("ps-test-1");

      fireEvent.click(screen.getByTestId("table-delete-policy-set-btn-ps-test-1"));
      expect(onDelete).toHaveBeenCalledWith(itemWithNotif);
    });
  });

  describe("PolicySetFilter", () => {
    it("handles search input, filter changes, and view mode toggle", () => {
      const onSearchChange = jest.fn();
      const onCategoryChange = jest.fn();
      const onScopeChange = jest.fn();
      const onViewModeChange = jest.fn();
      const onResetFilters = jest.fn();

      render(
        <PolicySetFilter
          searchQuery="test-query"
          onSearchChange={onSearchChange}
          categoryFilter="HARD_MANDATORY"
          scopeFilter="ALL"
          viewMode="cards"
          onViewModeChange={onViewModeChange}
          onResetFilters={onResetFilters}
          hasActiveFilters={true}
        />
      );

      const searchInput = screen.getByTestId("policy-set-search-input");
      fireEvent.change(searchInput, { target: { value: "new-query" } });
      expect(onSearchChange).toHaveBeenCalledWith("new-query");

      const clearBtn = screen.getByTestId("clear-filters-btn");
      fireEvent.click(clearBtn);
      expect(onResetFilters).toHaveBeenCalled();

      const compactRadio = screen.getByRole("radio", { name: /compact/i });
      fireEvent.click(compactRadio);
      expect(onViewModeChange).toHaveBeenCalledWith("compact");
    });
  });
});
