package org.weirdcanada.distro.tools

import net.liftweb.json._
import net.liftweb.common.Loggable
import net.liftweb.json.{pretty, render}
import net.liftweb.json.JsonAST._
import net.liftweb.json.JsonDSL._
import org.weirdcanada.distro.Config
import org.weirdcanada.distro.api.shopify.{Shopify, Metafield, Product}
import org.weirdcanada.distro.service.DatabaseManager
import org.weirdcanada.distro.util.NullEmailFactory
import org.weirdcanada.distro.data.{Sale, ConsignedItem}
import scala.io.Source
import net.liftweb.common.Full
import org.weirdcanada.distro.api.shopify.Variant
import org.weirdcanada.distro.util.AnyExtensions._
import scala.annotation.tailrec
import org.weirdcanada.distro.util.IdList
import org.weirdcanada.distro.util.Parse._
import org.weirdcanada.distro.data.Album

//
// Helper app to insert/update/delete consigned items in the database
//
object ConsignedItemApp extends App with Loggable {
  val config = Config.fromLiftProps
  val dbManager = new DatabaseManager(config)

  dbManager.connect
  //dbManager.createSchema

  sealed trait Mode
  case object Add extends Mode
  case object Show extends Mode
  case object Update extends Mode
  case object Delete extends Mode
  case object List extends Mode
  
  case class Args(
    mode: Mode = Show,
    id: Option[Long] = None,
    customerCost: Option[BigDecimal] = None,
    wholesaleCost: Option[BigDecimal] = None,
    markUp: Option[BigDecimal] = Some(BigDecimal(1.00)),
    consignor: Option[Long] = None,
    album: Option[Long] = None,
    notes: Option[String] = None,
    coverCondition: Option[String] = None,
    mediaCondition: Option[String] = None,
    sku: Option[String] = None
  )
  
  object Args {
    private object Default extends Args()
          
    def apply() = parseArgs(args.toList, Default)

    @tailrec
    private def parseArgs(remainingArgs: List[String], args: Args): Args = {
      remainingArgs match {
        case Nil =>
          args
          
        case "-a" :: tail =>
          parseArgs(tail, args.copy(mode = Add))
        case "-u" :: id :: tail =>
          parseArgs(tail, args.copy(mode = Update, id = Some(id.toLong)))
        case "-d" :: id :: tail =>
          parseArgs(tail, args.copy(mode = Delete, id = Some(id.toLong)))
        case "-s" :: id :: tail =>
          parseArgs(tail, args.copy(mode = Show, id = Some(id.toLong)))
        case "-l" :: tail =>
          parseArgs(tail, args.copy(mode = List))
        

        case "-sku" :: sku :: tail => // Note: Sku is automatically assigned. only use this to override the value
          parseArgs(tail, args.copy(sku = Some(sku)))
          
        case "-mediacond" :: cond :: tail =>
          parseArgs(tail, args.copy(mediaCondition = Some(cond)))
          
        case "-covercond" :: cond :: tail =>
          parseArgs(tail, args.copy(coverCondition = Some(cond)))
          
        case "-notes" :: notes :: tail =>
          parseArgs(tail, args.copy(notes = Some(notes)))
          
        case "-markup" :: markUp :: tail =>
          parseArgs(tail, args.copy(markUp = Some(BigDecimal(markUp))))
          
        case "-cost" :: cost :: tail =>
          parseArgs(tail, args.copy(customerCost = Some(BigDecimal(cost))))
          
        case "-wholesale" :: cost :: tail =>
          parseArgs(tail, args.copy(wholesaleCost = Some(BigDecimal(cost))))
          
        case "-consignor" :: id :: tail =>
          parseArgs(tail, args.copy(consignor = Some(id.toLong)))
          
        case "-album" :: id :: tail =>
          parseArgs(tail, args.copy(album = Some(id.toLong)))
          
        case unexpectedToken :: tail =>
          println("Unexpected token: " + unexpectedToken)
          sys.exit(1)
      }
    }
  }
  
  (new ConsignedItemApp(Args())).apply
}

import ConsignedItemApp._

class ConsignedItemApp(args: Args) {

  import ConsignedItem.mkDefaultSku
  // Helper method to display an error message and exit (or continue if the user chose to ignore errors)
  private def exit(code: Int, msg: => String): Nothing = {
    println(msg)
    sys.exit(code)
  }
  
  def print(consignedItem: ConsignedItem) {
    val json =
      "consignedItem" -> (
        ("id" -> consignedItem.id.is) ~ 
        ("sku" -> consignedItem.sku.is) ~
        ("customerCost" -> consignedItem.customerCost.is) ~
        ("wholesaleCost" -> consignedItem.wholesaleCost.is) ~
        ("markUp" -> consignedItem.markUp.is) ~
        ("coverCondition" -> consignedItem.coverCondition.is.toString) ~
        ("mediaCondition" -> consignedItem.mediaCondition.is.toString) ~
        ("additionalNotes" -> consignedItem.additionalNotes.is) ~
        ("album" -> consignedItem.album.obj.map(_.toString).getOrElse("")) ~
        ("consignor" -> consignedItem.consignor.obj.map(_.toString).getOrElse(""))
      )
      
    println(pretty(render(json)))    
  }
    
  def apply = {
    val consignedItem: ConsignedItem =
      (args.mode, args.id) match {
        case (Add, None) =>
          ConsignedItem.create

        case (Show | Update, Some(id)) =>
          ConsignedItem.findByKey(id)
            .getOrElse(exit(1, "ConsignedItem with id %s does not exist".format(id)))
        
        case (Delete, Some(id)) =>
          // TODO: add ON DELETE RESTRICT clause to the foreign key relation
          ConsignedItem.findByKey(id).map(ConsignedItem.delete_!)
          exit(0, "Deleted ConsignedItem %d".format(id))
        
        case (List, _) =>
          val allItems = ConsignedItem.findAll
          allItems.map(print)
          exit(0, "%d ConsignedItems".format(allItems.length))
          
        case _ =>
          exit(1, "Invalid options")
      }
    
    if (args.mode == Add || args.mode == Update) {
      args.customerCost map consignedItem.customerCost.set
      args.wholesaleCost map consignedItem.wholesaleCost.set
      args.markUp map consignedItem.markUp.set
      args.consignor map consignedItem.consignor.set
      args.album map consignedItem.album.set
      args.notes map consignedItem.additionalNotes.set
      args.coverCondition map ConsignedItem.Condition.withName map consignedItem.coverCondition.set
      args.mediaCondition map ConsignedItem.Condition.withName map consignedItem.mediaCondition.set
      args.sku map consignedItem.sku.set
      consignedItem.save
      
      // Assign a default SKU if necessary
      if (args.mode == Add && args.sku.isEmpty) {
        consignedItem.sku(mkDefaultSku(consignedItem))
        consignedItem.save
      }
    }

    print(consignedItem)
  } // def apply
}
