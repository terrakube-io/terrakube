import { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { axiosGraphQL } from "@/config/axiosConfig";
import LoadingFallback from "@/components/feedback/LoadingFallback";
import { ErrorState } from "@/components/feedback/ErrorState";

const UNAUTHORIZED_MESSAGE = "You are not authorized to view this run or the workspace does not exist.";

export const RunRedirectRoute = () => {
  const { orgName, wsName, runid } = useParams<{ orgName: string; wsName: string; runid: string }>();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let isMounted = true;

    async function resolveAndRedirect() {
      if (!orgName || !wsName || !runid) {
        setError(UNAUTHORIZED_MESSAGE);
        return;
      }

      const cleanRunId = runid.replace(/^run-/, "");

      try {
        const body = {
          query: `query ResolveRun($orgFilter: String, $wsFilter: String, $jobFilter: String) {
            organization(filter: $orgFilter) {
              edges {
                node {
                  id
                  name
                  workspace(filter: $wsFilter) {
                    edges {
                      node {
                        id
                        name
                        job(filter: $jobFilter) {
                          edges {
                            node {
                              id
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }`,
          variables: {
            orgFilter: `name=="${orgName}"`,
            wsFilter: `name=="${wsName}"`,
            jobFilter: `id=="${cleanRunId}"`,
          },
        };

        const response = await axiosGraphQL.post("", body, {
          headers: { "Content-Type": "application/json" },
        });

        if (!isMounted) return;

        if (response.data?.errors?.length) {
          setError(UNAUTHORIZED_MESSAGE);
          return;
        }

        const orgNode = response.data?.data?.organization?.edges?.[0]?.node;
        const wsNode = orgNode?.workspace?.edges?.[0]?.node;
        const jobNode = wsNode?.job?.edges?.[0]?.node;

        const orgId = orgNode?.id;
        const wsId = wsNode?.id;
        const jobId = jobNode?.id;

        if (orgId && wsId && jobId) {
          navigate(`/organizations/${orgId}/workspaces/${wsId}/runs/${cleanRunId}`, { replace: true });
        } else {
          setError(UNAUTHORIZED_MESSAGE);
        }
      } catch {
        if (!isMounted) return;
        setError(UNAUTHORIZED_MESSAGE);
      }
    }

    resolveAndRedirect();

    return () => {
      isMounted = false;
    };
  }, [orgName, wsName, runid, navigate]);

  if (error) {
    return <ErrorState status={403} title="Not Authorized" message={error} showHomeLink />;
  }

  return <LoadingFallback />;
};
