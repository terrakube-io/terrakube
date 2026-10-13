import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { RunRedirectRoute } from "../RunRedirectRoute";

const mockPost = jest.fn();
const mockNavigate = jest.fn();

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  axiosGraphQL: {
    post: (...args: unknown[]) => mockPost(...args),
  },
  default: {},
  getErrorMessage: () => "error",
}));

jest.mock("react-router-dom", () => {
  const actual = jest.requireActual("react-router-dom");
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  };
});

describe("RunRedirectRoute", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("successfully resolves org, workspace, and job and navigates to run", async () => {
    mockPost.mockResolvedValueOnce({
      data: {
        data: {
          organization: {
            edges: [
              {
                node: {
                  id: "org-uuid-123",
                  name: "simple",
                  workspace: {
                    edges: [
                      {
                        node: {
                          id: "ws-uuid-456",
                          name: "sensitive-value-leak",
                          job: {
                            edges: [
                              {
                                node: {
                                  id: "5",
                                },
                              },
                            ],
                          },
                        },
                      },
                    ],
                  },
                },
              },
            ],
          },
        },
      },
    });

    render(
      <MemoryRouter initialEntries={["/app/simple/sensitive-value-leak/runs/run-5"]}>
        <Routes>
          <Route path="/app/:orgName/:wsName/runs/:runid" element={<RunRedirectRoute />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith("/organizations/org-uuid-123/workspaces/ws-uuid-456/runs/5", {
        replace: true,
      });
    });

    expect(mockPost).toHaveBeenCalledWith(
      "",
      expect.objectContaining({
        variables: {
          orgFilter: 'name=="simple"',
          wsFilter: 'name=="sensitive-value-leak"',
          jobFilter: 'id=="5"',
        },
      }),
      expect.anything()
    );
  });

  it("handles plain numeric run id without run- prefix", async () => {
    mockPost.mockResolvedValueOnce({
      data: {
        data: {
          organization: {
            edges: [
              {
                node: {
                  id: "org-uuid-123",
                  name: "simple",
                  workspace: {
                    edges: [
                      {
                        node: {
                          id: "ws-uuid-456",
                          name: "prod",
                          job: {
                            edges: [
                              {
                                node: {
                                  id: "42",
                                },
                              },
                            ],
                          },
                        },
                      },
                    ],
                  },
                },
              },
            ],
          },
        },
      },
    });

    render(
      <MemoryRouter initialEntries={["/app/simple/prod/runs/42"]}>
        <Routes>
          <Route path="/app/:orgName/:wsName/runs/:runid" element={<RunRedirectRoute />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith("/organizations/org-uuid-123/workspaces/ws-uuid-456/runs/42", {
        replace: true,
      });
    });

    expect(mockPost).toHaveBeenCalledWith(
      "",
      expect.objectContaining({
        variables: {
          orgFilter: 'name=="simple"',
          wsFilter: 'name=="prod"',
          jobFilter: 'id=="42"',
        },
      }),
      expect.anything()
    );
  });

  it("shows 403 Not Authorized when organization is not found", async () => {
    mockPost.mockResolvedValueOnce({
      data: {
        data: {
          organization: {
            edges: [],
          },
        },
      },
    });

    render(
      <MemoryRouter initialEntries={["/app/nonexistent/ws/runs/1"]}>
        <Routes>
          <Route path="/app/:orgName/:wsName/runs/:runid" element={<RunRedirectRoute />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText("Not Authorized")).toBeInTheDocument();
      expect(
        screen.getByText("You are not authorized to view this run or the workspace does not exist.")
      ).toBeInTheDocument();
    });
  });

  it("shows 403 Not Authorized when workspace is not found", async () => {
    mockPost.mockResolvedValueOnce({
      data: {
        data: {
          organization: {
            edges: [
              {
                node: {
                  id: "org-1",
                  name: "simple",
                  workspace: {
                    edges: [],
                  },
                },
              },
            ],
          },
        },
      },
    });

    render(
      <MemoryRouter initialEntries={["/app/simple/missing-ws/runs/1"]}>
        <Routes>
          <Route path="/app/:orgName/:wsName/runs/:runid" element={<RunRedirectRoute />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText("Not Authorized")).toBeInTheDocument();
      expect(
        screen.getByText("You are not authorized to view this run or the workspace does not exist.")
      ).toBeInTheDocument();
    });
  });

  it("shows 403 Not Authorized when job is not found in workspace", async () => {
    mockPost.mockResolvedValueOnce({
      data: {
        data: {
          organization: {
            edges: [
              {
                node: {
                  id: "org-1",
                  name: "simple",
                  workspace: {
                    edges: [
                      {
                        node: {
                          id: "ws-1",
                          name: "dev",
                          job: {
                            edges: [],
                          },
                        },
                      },
                    ],
                  },
                },
              },
            ],
          },
        },
      },
    });

    render(
      <MemoryRouter initialEntries={["/app/simple/dev/runs/999"]}>
        <Routes>
          <Route path="/app/:orgName/:wsName/runs/:runid" element={<RunRedirectRoute />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText("Not Authorized")).toBeInTheDocument();
      expect(
        screen.getByText("You are not authorized to view this run or the workspace does not exist.")
      ).toBeInTheDocument();
    });
  });

  it("shows 403 Not Authorized on GraphQL error", async () => {
    mockPost.mockResolvedValueOnce({
      data: {
        errors: [{ message: "ReadPermission Denied" }],
      },
    });

    render(
      <MemoryRouter initialEntries={["/app/forbidden/ws/runs/1"]}>
        <Routes>
          <Route path="/app/:orgName/:wsName/runs/:runid" element={<RunRedirectRoute />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText("Not Authorized")).toBeInTheDocument();
      expect(
        screen.getByText("You are not authorized to view this run or the workspace does not exist.")
      ).toBeInTheDocument();
    });
  });
});
