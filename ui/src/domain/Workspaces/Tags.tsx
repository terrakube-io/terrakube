import { Select } from "antd";
import { useEffect, useMemo, useState } from "react";
import axiosInstance from "../../config/axiosConfig";
import { Tag, ApiWorkspaceTag } from "../types";
type Props = {
  organizationId: string;
  workspaceId: string;
  manageWorkspace: boolean;
};
export const Tags = ({ organizationId, workspaceId, manageWorkspace }: Props) => {
  const [tags, setTags] = useState<Tag[]>([]);
  const [currentTags, setCurrentTags] = useState<ApiWorkspaceTag[]>([]);
  const [loading, setLoading] = useState(false);

  const selectedTags = useMemo(() => {
    return currentTags.map(function (tag) {
      return tag.attributes?.tagId;
    });
  }, [currentTags]);

  const allTags = useMemo(() => {
    return tags.map(function (tag) {
      return { label: tag.attributes.name, value: tag.id };
    });
  }, [tags]);

  const handleSelect = (tagId: string) => {
    // create a new Tag
    if (!isGuid(tagId)) createNewTag(tagId);
    else addTagToWorkspace(tagId);
  };
  const createNewTag = (tagName: string) => {
    const body = {
      data: {
        type: "tag",
        attributes: {
          name: tagName,
        },
      },
    };

    //search for existing tag if you type the name and hit enter
    let existingTagId;
    axiosInstance
      .get(`organization/${organizationId}/tag`, {
        params: { 'filter[tag]': `name==${tagName}` },
      })
      .then((oldTag) => {
      existingTagId = oldTag.data?.data[0]?.id;

      if (existingTagId === undefined) {
        axiosInstance
          .post(`organization/${organizationId}/tag`, body, {
            headers: {
              "Content-Type": "application/vnd.api+json",
            },
          })
          .then((response) => {
            setTags((prev) => [...prev, response.data?.data]);
            addTagToWorkspace(response.data?.data?.id);
          });
      } else {
        addTagToWorkspace(existingTagId);
      }
    });
  };
  const addTagToWorkspace = (tagId: string) => {
    const body = {
      data: {
        type: "workspacetag",
        attributes: {
          tagId: tagId,
        },
      },
    };

    axiosInstance
      .post(`organization/${organizationId}/workspace/${workspaceId}/workspaceTag`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        setCurrentTags((prev) => [...prev, response.data.data]);
      });
  };
  const handleDeselect = (tagId: string) => {
    let currentTag: string | undefined = tagId;
    if (!isGuid(tagId)) {
      currentTag = tags.find((x) => x.attributes.name === tagId)?.id;
    }

    const id = currentTags.find((x) => x.attributes.tagId === currentTag)?.id;

    axiosInstance.delete(`organization/${organizationId}/workspace/${workspaceId}/workspaceTag/${id}`).then(() => {
      const currentTagsFilter = currentTags.filter(function (x) {
        return x.id !== id;
      });
      setCurrentTags(currentTagsFilter);
    });
  };

  function isGuid(value: string) {
    const regex = /[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}/i;
    const match = regex.exec(value);
    return match != null;
  }
  const loadTags = () => {
    axiosInstance.get(`organization/${organizationId}/workspace/${workspaceId}/workspaceTag`).then((response) => {
      setCurrentTags(response.data.data);
      axiosInstance.get(`organization/${organizationId}/tag`).then((res) => {
        setTags(res.data.data);
        setLoading(false);
      });
    });
  };
  useEffect(() => {
    loadTags();
  }, [workspaceId]);

  return (
    <Select
      mode="tags"
      style={{ width: "100%" }}
      value={selectedTags}
      options={allTags}
      placeholder="Add a tag"
      loading={loading}
      disabled={!manageWorkspace}
      onSelect={handleSelect}
      onDeselect={handleDeselect}
    />
  );
};
