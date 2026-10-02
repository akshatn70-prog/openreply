import * as meta from "@/lib/meta/client";
import type { InstagramContext } from "./context";

export async function sendPrivateReply({
  context,
  instagramAccountId,
  commentId,
  message,
}: {
  context: InstagramContext;
  instagramAccountId: string;
  commentId: string;
  message: string;
  postId?: string;
}) {
  return meta.sendPrivateReply(
    context.accessToken,
    instagramAccountId,
    commentId,
    message
  );
}

export async function sendPrivateReplyWithButton({
  context,
  instagramAccountId,
  commentId,
  text,
  buttonTitle,
  payload,
}: {
  context: InstagramContext;
  instagramAccountId: string;
  commentId: string;
  text: string;
  buttonTitle: string;
  payload: string;
  postId?: string;
}) {
  return meta.sendPrivateReplyWithButton(
    context.accessToken,
    instagramAccountId,
    commentId,
    text,
    buttonTitle,
    payload
  );
}

export async function sendDirectMessageWithButton({
  context,
  instagramAccountId,
  userId,
  text,
  buttonTitle,
  payload,
}: {
  context: InstagramContext;
  instagramAccountId: string;
  userId: string;
  text: string;
  buttonTitle: string;
  payload: string;
}) {
  return meta.sendDirectMessageWithButton(
    context.accessToken,
    instagramAccountId,
    userId,
    text,
    buttonTitle,
    payload
  );
}

export async function sendPrivateReplyWithLinkButton({
  context,
  instagramAccountId,
  commentId,
  text,
  buttons,
}: {
  context: InstagramContext;
  instagramAccountId: string;
  commentId: string;
  text: string;
  buttons: meta.LinkButton[];
  postId?: string;
}) {
  return meta.sendPrivateReplyWithLinkButton(
    context.accessToken,
    instagramAccountId,
    commentId,
    text,
    buttons
  );
}

export async function sendDirectMessage({
  context,
  instagramAccountId,
  userId,
  message,
}: {
  context: InstagramContext;
  instagramAccountId: string;
  userId: string;
  message: string;
}) {
  return meta.sendDirectMessage(
    context.accessToken,
    instagramAccountId,
    userId,
    message
  );
}

export async function sendDirectMessageWithLinkButton({
  context,
  instagramAccountId,
  userId,
  text,
  buttons,
}: {
  context: InstagramContext;
  instagramAccountId: string;
  userId: string;
  text: string;
  buttons: meta.LinkButton[];
}) {
  return meta.sendDirectMessageWithLinkButton(
    context.accessToken,
    instagramAccountId,
    userId,
    text,
    buttons
  );
}

export async function sendCommentReply({
  context,
  commentId,
  message,
}: {
  context: InstagramContext;
  commentId: string;
  message: string;
  postId?: string;
}) {
  return meta.sendCommentReply(context.accessToken, commentId, message);
}
