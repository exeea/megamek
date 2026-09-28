package megamek.client.ui.clientGUI.boardview.gpu;
import java.util.*;
import com.badlogic.gdx.math.*;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
class BoardMinesSeamProbeTest {
 @Test void locate() throws Exception {
  GdxNativesLoader.load(); var scene=GpuRoadSourceTest.minesScene(); GpuRiverTerrainSmokeTest.tune(.94f,true);
  var camera=new BoardCamera(); camera.resize(1280,1440); camera.setIsometric(true); camera.camera.zoom=.2f;
  camera.center(BoardGeometry.center(new Coords(7,14),0));
  for(var xy:List.of(new float[]{157.5f,538.5f},new float[]{45.5f,521.5f})) {
   var origin=new Vector3(xy[0]/640-1,xy[1]/720-1,-1).prj(camera.camera.invProjectionView);
   var end=new Vector3(xy[0]/640-1,xy[1]/720-1,1).prj(camera.camera.invProjectionView);
   var ray=new com.badlogic.gdx.math.collision.Ray(origin,end.sub(origin).nor());
   var ground=new Vector3(origin).mulAdd(ray.direction,-origin.z/ray.direction.z);
   System.out.println("PIXEL "+Arrays.toString(xy)+" ground="+ground+" RAY="+ray);
   var surfaces=new HashMap<Coords,BoardSurface>();
   for(var tile:scene.tiles()) if(new Vector3(BoardGeometry.center(tile.coords(),0)).dst2(ground)<150*150) surfaces.put(tile.coords(),new BoardSurface(scene,tile));
   var near=new TreeMap<Float,String>();
   for(var surface:surfaces.values()) {
    var faces=new ArrayList<>(surface.groundFaces()); faces.addAll(surface.walls(scene,BoardGeometry.floor(scene),surfaces));
    for(var f:faces) {
     var pp=List.of(f.a(),f.b(),f.c());
     for(int i=0;i<3;i++) {
      var a=pp.get(i); var b=pp.get((i+1)%3);
      var pa=new Vector3(a).prj(camera.camera.combined); var pb=new Vector3(b).prj(camera.camera.combined);
      pa.set((pa.x+1)*640,(pa.y+1)*720,0); pb.set((pb.x+1)*640,(pb.y+1)*720,0);
      var d=new Vector3(pb).sub(pa); var v=new Vector3(xy[0],xy[1],0).sub(pa);
      float t=Math.clamp(v.dot(d)/d.len2(),0,1); float distance=new Vector3(pa).mulAdd(d,t).dst2(new Vector3(xy[0],xy[1],0));
      if(distance<4) near.put(distance,surface.tile.coords()+" "+f.finish()+" "+a+" -> "+b+" screen "+pa+"->"+pb);
     }
    }
   }
   near.entrySet().stream().limit(12).forEach(System.out::println);
  }
 }
}
